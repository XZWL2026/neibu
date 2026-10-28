package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Reads the saved supplier's catalog or task list; never starts billable work. */
public final class HttpArkConnectionProbe implements ArkConnectionProbe {
  private final ArkConnectionStore store;
  private final HttpClient client;
  private final Duration timeout;
  public HttpArkConnectionProbe(ArkConnectionStore store, HttpClient client, Duration timeout) {
    if(client.followRedirects()!=HttpClient.Redirect.NEVER || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Invalid probe HTTP configuration");
    this.store=store; this.client=client; this.timeout=timeout;
  }
  @Override public Result test(String providerId) {
    ArkConnectionStore.Connection connection;
    try { connection=store.load(providerId); }
    catch(ArkConnectionStore.NotEditable unavailable) { return new Result(0,false,"连接配置暂不可读取，请联系部署管理员。"); }
    if(connection.apiKey()==null || connection.apiKey().isBlank() || connection.baseUrl()==null || connection.baseUrl().isBlank())
      return new Result(connection.version(),false,"请先保存完整的连接地址和 API Key。");
    try {
      if(ProviderSettings.newApiDoubao(connection.baseUrl())) {
        var catalog=new HttpArkModelCatalog(store,new ProviderHttp(client,new com.fasterxml.jackson.databind.ObjectMapper(),timeout)).list(connection);
        return new Result(connection.version(),catalog.success(),catalog.success()
            ? "连接与鉴权通过。模型权限、余额及生成能力以实际任务结果为准。" : catalog.message());
      }
      String base=ProviderSettings.providerEndpoint(connection.baseUrl()).toString();
      var request=HttpRequest.newBuilder(URI.create(base+(ProviderSettings.zex(connection.baseUrl()) ? "/models" : "/contents/generations/tasks?page_size=1")))
          .timeout(timeout).header("Authorization","Bearer "+connection.apiKey()).GET().build();
      int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
      if(status==200) return new Result(connection.version(),true,"连接与鉴权通过。模型权限、余额及生成能力以实际任务结果为准。");
      return new Result(connection.version(),false,status==401 || status==403
          ? "供应商拒绝鉴权或无查询权限，请检查 API Key 和供应商控制台授权。"
          : "供应商连接验证失败，请检查服务状态和连接地址。");
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Result(connection.version(),false,"连接验证已中断，请重试。");
    } catch(IOException | IllegalArgumentException failure) {
      return new Result(connection.version(),false,"无法连接供应商，请检查网络和连接地址后重试。");
    }
  }
}

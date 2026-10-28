package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL",matches=".+")
class AdminBootstrapMysqlTest {
  @Test void productionStartupRejectsMissingOrInvalidBootstrapWithoutLoggingValues() throws Exception {
    try (var db=new ReleaseAuthMysqlTest.Database()) {
      try (var missing=launch(db,Map.of())) {
        Result result=missing.finish();
        assertNotEquals(0,result.exit());
        assertTrue(result.output().contains("Administrator table is empty"));
        assertTrue(result.output().contains("IAM_BOOTSTRAP_ADMIN_USERNAME"));
      }
      String username=UUID.randomUUID().toString();
      String invalidPassword=UUID.randomUUID().toString().substring(0,7);
      try (var invalid=launch(db,Map.of("IAM_BOOTSTRAP_ADMIN_USERNAME",username,"IAM_BOOTSTRAP_ADMIN_PASSWORD",invalidPassword))) {
        Result result=invalid.finish();
        assertNotEquals(0,result.exit());
        assertTrue(result.output().contains("Administrator table is empty"));
        assertFalse(result.output().contains(username));
        assertFalse(result.output().contains(invalidPassword));
      }
      assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin",Integer.class));
    }
  }
  @Test void concurrentJvmStartupCreatesOnePrimaryAndLaterEnvironmentsAreIgnored() throws Exception {
    try (var db=new ReleaseAuthMysqlTest.Database()) {
      Map<String,String> firstEnv=Map.of("IAM_BOOTSTRAP_ADMIN_USERNAME",UUID.randomUUID().toString(),"IAM_BOOTSTRAP_ADMIN_PASSWORD",UUID.randomUUID().toString());
      Map<String,String> secondEnv=Map.of("IAM_BOOTSTRAP_ADMIN_USERNAME",UUID.randomUUID().toString(),"IAM_BOOTSTRAP_ADMIN_PASSWORD",UUID.randomUUID().toString());
      try (var first=launch(db,firstEnv); var second=launch(db,secondEnv)) {
        Result a=first.finish(),b=second.finish();
        assertEquals(0,a.exit(),a.output()); assertEquals(0,b.exit(),b.output());
        for(var secret:firstEnv.values()) { assertFalse(a.output().contains(secret)); assertFalse(b.output().contains(secret)); }
        for(var secret:secondEnv.values()) { assertFalse(a.output().contains(secret)); assertFalse(b.output().contains(secret)); }
      }
      assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin WHERE is_primary=TRUE AND role='ADMIN'",Integer.class));
      String username=db.jdbc.queryForObject("SELECT username FROM iam_admin",String.class);
      String password=firstEnv.get("IAM_BOOTSTRAP_ADMIN_USERNAME").equals(username)?firstEnv.get("IAM_BOOTSTRAP_ADMIN_PASSWORD"):secondEnv.get("IAM_BOOTSTRAP_ADMIN_PASSWORD");
      String hash=db.jdbc.queryForObject("SELECT password_hash FROM iam_admin",String.class);
      assertTrue(new com.xzwl.cloud.iam.infrastructure.account.BCryptPasswordHasher().matches(password,hash));
      // Any existing account suppresses bootstrap, including a table containing no primary.
      db.jdbc.update("UPDATE iam_admin SET is_primary=FALSE,role='NORMAL_ADMIN',enabled=FALSE");
      try (var restarted=launch(db,Map.of("IAM_BOOTSTRAP_ADMIN_USERNAME","invalid name","IAM_BOOTSTRAP_ADMIN_PASSWORD","bad"))) {
        Result result=restarted.finish(); assertEquals(0,result.exit(),result.output());
      }
      assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin",Integer.class));
      assertEquals(username,db.jdbc.queryForObject("SELECT username FROM iam_admin",String.class));
      assertEquals(hash,db.jdbc.queryForObject("SELECT password_hash FROM iam_admin",String.class));
      assertEquals("NORMAL_ADMIN",db.jdbc.queryForObject("SELECT role FROM iam_admin",String.class));
      assertFalse(db.jdbc.queryForObject("SELECT enabled FROM iam_admin",Boolean.class));
    }
  }
  private static Probe launch(ReleaseAuthMysqlTest.Database db,Map<String,String> bootstrap) throws Exception {
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),StartupProbe.class.getName(),db.url);
    process.environment().remove("IAM_BOOTSTRAP_ADMIN_USERNAME");
    process.environment().remove("IAM_BOOTSTRAP_ADMIN_PASSWORD");
    process.environment().putAll(bootstrap);
    Path output=Files.createTempFile("iam-bootstrap-probe-",".log");
    return new Probe(process.redirectErrorStream(true).redirectOutput(output.toFile()).start(),output);
  }
  private record Result(int exit,String output) {}
  private record Probe(Process process,Path output) implements AutoCloseable {
    Result finish() throws Exception {
      assertTrue(process.waitFor(60,TimeUnit.SECONDS),"Bootstrap process timed out");
      return new Result(process.exitValue(),Files.readString(output));
    }
    public void close() throws Exception { if(process.isAlive()) process.destroyForcibly().waitFor(); Files.deleteIfExists(output); }
  }
  public static class StartupProbe {
    public static void main(String[] args) {
      try(var app=new SpringApplicationBuilder(TemplateApplication.class).run(
          "--spring.config.location=classpath:/application.yml","--spring.profiles.active=prod",
          "--spring.datasource.url="+args[0],"--spring.datasource.username=root","--spring.datasource.password=",
          "--server.port=0","--server.address=127.0.0.1","--logging.level.root=ERROR","--spring.main.banner-mode=off")) {
        System.out.println("BOOTSTRAP_OK");
      }
    }
  }
}

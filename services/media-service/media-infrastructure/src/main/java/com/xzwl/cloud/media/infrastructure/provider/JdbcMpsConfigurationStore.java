package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.MpsConfigurationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcMpsConfigurationStore implements MpsConfigurationStore {
  private final JdbcTemplate jdbc;
  private final Configuration fallback;
  private final ObjectMapper json=new ObjectMapper();
  private record Policy(java.util.List<MpsConfigurationStore.EnhancementTemplate> enhancementTemplates,java.util.List<MpsConfigurationStore.BitrateProfile> bitrateProfiles) {}
  public JdbcMpsConfigurationStore(JdbcTemplate jdbc,Configuration fallback) { this.jdbc=jdbc;this.fallback=fallback; }
  public Configuration load() {
    var rows=jdbc.query("SELECT * FROM media_mps_configuration WHERE id=1",(r,n)->{
      Policy policy=decode(r.getString("enhancement_policy_json"));
      return new Configuration(r.getLong("version"),
        r.getString("secret_id"),r.getString("secret_key"),r.getString("region"),r.getString("bucket"),r.getString("bucket_region"),
        r.getString("output_dir"),r.getLong("template_720p"),r.getLong("template_1080p"),r.getLong("template_4k"),policy==null?null:policy.enhancementTemplates(),policy==null?null:policy.bitrateProfiles());});
    return rows.isEmpty()?fallback:rows.getFirst();
  }
  public Configuration save(Configuration r) {
    var current=load();
    if(r.version()!=current.version()) throw new VersionConflict();
    var templates=r.enhancementTemplates()==null?current.enhancementTemplates():r.enhancementTemplates();
    var profiles=r.bitrateProfiles()==null?current.bitrateProfiles():r.bitrateProfiles();
    MpsConfigurationStore.validatePolicy(templates,profiles);
    String secret=r.secretKey()==null||r.secretKey().isBlank()?current.secretKey():r.secretKey();
    if(r.version()<0||r.version()==Long.MAX_VALUE||r.secretId()==null||!r.secretId().matches("[A-Za-z0-9_-]{1,256}")
        ||secret==null||!secret.matches("[A-Za-z0-9_+=/.-]{1,4096}")
        ||(!r.secretId().equals(current.secretId())&&(r.secretKey()==null||r.secretKey().isBlank()))
        ||r.region()==null||!r.region().matches("[a-z0-9-]{1,64}")||r.bucketRegion()==null||!r.bucketRegion().matches("[a-z0-9-]{1,64}")
        ||r.bucket()==null||!r.bucket().matches("[a-z0-9-]+-[0-9]+")||r.bucket().length()>128
        ||r.outputDir()==null||!r.outputDir().matches("[A-Za-z0-9_/-]{0,512}")||r.outputDir().contains("//")
        ||r.template720p()<(templates==null?1:0)||r.template1080p()<(templates==null?1:0)||r.template4k()<(templates==null?1:0)
        ||r.template720p()>9007199254740991L||r.template1080p()>9007199254740991L||r.template4k()>9007199254740991L)
      throw new IllegalArgumentException("Invalid MPS configuration");
    var saved=new Configuration(r.version()+1,r.secretId(),secret,r.region(),r.bucket(),r.bucketRegion(),r.outputDir(),r.template720p(),r.template1080p(),r.template4k(),templates,profiles);
    String policy=templates==null?null:encode(new Policy(templates,profiles));
    try {
      int changed=r.version()==0 ? jdbc.update("INSERT INTO media_mps_configuration(id,version,secret_id,secret_key,region,bucket,bucket_region,output_dir,template_720p,template_1080p,template_4k,enhancement_policy_json) VALUES(1,?,?,?,?,?,?,?,?,?,?,?)",saved.version(),saved.secretId(),saved.secretKey(),saved.region(),saved.bucket(),saved.bucketRegion(),saved.outputDir(),saved.template720p(),saved.template1080p(),saved.template4k(),policy)
          :jdbc.update("UPDATE media_mps_configuration SET version=?,secret_id=?,secret_key=?,region=?,bucket=?,bucket_region=?,output_dir=?,template_720p=?,template_1080p=?,template_4k=?,enhancement_policy_json=? WHERE id=1 AND version=?",saved.version(),saved.secretId(),saved.secretKey(),saved.region(),saved.bucket(),saved.bucketRegion(),saved.outputDir(),saved.template720p(),saved.template1080p(),saved.template4k(),policy,r.version());
      if(changed!=1) throw new VersionConflict();
    } catch(DuplicateKeyException conflict) { throw new VersionConflict(); }
    return saved;
  }
  private String encode(Policy policy) {
    try { return json.writeValueAsString(policy); } catch(Exception invalid) { throw new IllegalArgumentException("Invalid MPS policy"); }
  }
  private Policy decode(String value) {
    try { return value==null?null:json.readValue(value,Policy.class); } catch(Exception invalid) { throw new IllegalStateException("Invalid stored MPS policy"); }
  }
}

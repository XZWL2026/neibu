package com.xzwl.cloud.media.infrastructure.task;
import com.xzwl.cloud.media.application.task.EnhancementQuoteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
public final class JdbcEnhancementQuoteStore implements EnhancementQuoteStore {
  private final JdbcTemplate jdbc;private final ObjectMapper json;
  public JdbcEnhancementQuoteStore(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
  public void save(Quote quote) {
    try {jdbc.update("INSERT INTO media_enhancement_quote(id,owner_id,expires_at,quote_json) VALUES(?,?,?,?)",quote.id(),quote.ownerId(),quote.expiresAt().toEpochMilli(),json.writeValueAsString(quote));}
    catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("invalid quote",failure);}
  }
  public Optional<Quote> find(String ownerId,String id) {
    return jdbc.query("SELECT quote_json FROM media_enhancement_quote WHERE id=? AND owner_id=?",(row,index)->{
      try {return json.readValue(row.getString(1),Quote.class);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("invalid stored quote",failure);}
    },id,ownerId).stream().findFirst();
  }
}

package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.ConsumptionStore;
import com.xzwl.cloud.billing.infrastructure.JdbcConsumptionStore;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class ConsumptionDimensionsTest {
  @Test void readsDimensionsFromOriginalChargeSnapshot() {
    var video = read("owner\nVIDEO\nseedance-2.0\n720p\n1\n12");
    assertEquals("720p", video.resolution());
    assertEquals(1, video.count());
    assertEquals(12, video.durationSeconds());
    assertEquals("12.4000", video.amount4());
    var image = read("owner\nIMAGE\nimage-model\n2K\n4\n0");
    assertEquals("2K", image.resolution());
    assertEquals(4, image.count());
    assertEquals(0, image.durationSeconds());
  }

  @Test void missingAndMalformedHistoricalDimensionsRemainUnknown() {
    for (String facts : new String[]{null, "legacy", "owner\nVIDEO\nmodel\n\n-1\n99999999999999999999"}) {
      var row = read(facts);
      assertNull(row.resolution());
      assertNull(row.count());
      assertNull(row.durationSeconds());
      assertEquals("12.4000", row.amount4());
    }
  }

  @Test void rejectsOversizedPageBeforeQueryingDatabase() {
    var store = new JdbcConsumptionStore(new JdbcTemplate());
    assertThrows(IllegalArgumentException.class, () -> store.search(
        new ConsumptionStore.Filter("owner", null, null, null, Instant.now(), 0, 200)));
  }

  private static ConsumptionStore.Row read(String facts) {
    ResultSet result = (ResultSet) Proxy.newProxyInstance(ConsumptionDimensionsTest.class.getClassLoader(),
        new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
          if (method.getName().equals("getString")) return switch ((String) args[0]) {
            case "request_facts" -> facts;
            case "owner_id" -> "owner";
            default -> "test-id";
          };
          if (method.getName().equals("getBigDecimal")) return switch ((String) args[0]) {
            case "points4" -> new BigDecimal("-124.0000");
            case "refunded" -> BigDecimal.ZERO;
            case "amount4" -> new BigDecimal("12.4000");
            case "rate4" -> new BigDecimal("10.0000");
            default -> throw new AssertionError(args[0]);
          };
          throw new AssertionError(method.getName());
        });
    var jdbc = new JdbcTemplate() {
      @Override public <T> List<T> query(PreparedStatementCreator statement, RowMapper<T> mapper) {
        try { return List.of(mapper.mapRow(result, 0)); }
        catch (SQLException error) { throw new AssertionError(error); }
      }
    };
    return new JdbcConsumptionStore(jdbc).get("task");
  }
}

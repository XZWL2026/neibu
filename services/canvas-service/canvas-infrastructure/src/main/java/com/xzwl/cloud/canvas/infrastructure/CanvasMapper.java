package com.xzwl.cloud.canvas.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CanvasMapper extends BaseMapper<CanvasRow> {
  @Select("""
      SELECT id, owner_id, name, content_json, version, created_at, updated_at, deleted
      FROM canvas_document
      WHERE owner_id=#{ownerId} AND deleted=FALSE
      ORDER BY updated_at DESC, id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<CanvasRow> findPage(@Param("ownerId") String ownerId, @Param("offset") int offset,
      @Param("limit") int limit);

  @Update("""
      UPDATE canvas_document SET name=#{row.name}, content_json=#{row.contentJson}, version=#{row.version},
          updated_at=#{row.updatedAt}, deleted=#{row.deleted}
      WHERE id=#{row.id} AND owner_id=#{row.ownerId} AND version=#{expectedVersion} AND deleted=FALSE
      """)
  int compareAndSave(@Param("row") CanvasRow row, @Param("expectedVersion") long expectedVersion);
}

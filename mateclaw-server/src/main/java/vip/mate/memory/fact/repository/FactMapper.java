package vip.mate.memory.fact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.memory.fact.model.FactEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fact mapper — limited write operations enforce the core invariant:
 * - Derived columns: only FactProjectionBuilder may write (via upsertDerived)
 * - Accumulated columns: only bumpUseCount may write
 *
 * @author MateClaw Team
 */
@Mapper
public interface FactMapper extends BaseMapper<FactEntity> {

    /**
     * Bump use_count and last_used_at for the given fact IDs.
     * This is the ONLY path that writes accumulated columns.
     */
    @Update("""
        <script>
        UPDATE mate_fact
        SET use_count = use_count + 1, last_used_at = #{now}, update_time = #{now}
        WHERE id IN
        <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
        AND deleted = 0
        </script>
        """)
    void bumpUseCount(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Soft-delete facts whose source_ref is no longer in the canonical set.
     * Used during full rebuild to remove stale projections.
     */
    @Update("""
        <script>
        UPDATE mate_fact SET deleted = 1, update_time = #{now}
        WHERE agent_id = #{agentId} AND deleted = 0
        AND source_ref NOT IN
        <foreach item='ref' collection='keepSet' open='(' separator=',' close=')'>#{ref}</foreach>
        </script>
        """)
    void deleteByAgentIdAndSourceRefNotIn(@Param("agentId") Long agentId,
                                          @Param("keepSet") List<String> keepSet,
                                          @Param("now") LocalDateTime now);

    /**
     * Soft-delete stale facts for a single shared canonical file after an
     * incremental rebuild. Without this, deleted / forgotten sections remain
     * recallable until the next full projection rebuild.
     */
    @Update("""
        <script>
        UPDATE mate_fact SET deleted = 1, update_time = #{now}
        WHERE agent_id = #{agentId} AND deleted = 0
        AND source_ref LIKE CONCAT(#{sourcePrefix}, '%')
        <choose>
          <when test='keepSet != null and keepSet.size() > 0'>
            AND source_ref NOT IN
            <foreach item='ref' collection='keepSet' open='(' separator=',' close=')'>#{ref}</foreach>
          </when>
        </choose>
        </script>
        """)
    void softDeleteByAgentIdAndSourceRefPrefixNotIn(@Param("agentId") Long agentId,
                                                    @Param("sourcePrefix") String sourcePrefix,
                                                    @Param("keepSet") List<String> keepSet,
                                                    @Param("now") LocalDateTime now);

    /**
     * Owner-aware variant for PERSONAL rows. Scope + owner_key are part of the
     * logical projection key because different owners can have the same source
     * filename and section name.
     */
    @Update("""
        <script>
        UPDATE mate_fact SET deleted = 1, update_time = #{now}
        WHERE agent_id = #{agentId} AND deleted = 0
        AND source_ref LIKE CONCAT(#{sourcePrefix}, '%')
        AND scope = #{scope}
        <choose>
          <when test='ownerKey != null'>
            AND owner_key = #{ownerKey}
          </when>
          <otherwise>
            AND (owner_key IS NULL OR owner_key = '')
          </otherwise>
        </choose>
        <choose>
          <when test='keepSet != null and keepSet.size() > 0'>
            AND source_ref NOT IN
            <foreach item='ref' collection='keepSet' open='(' separator=',' close=')'>#{ref}</foreach>
          </when>
        </choose>
        </script>
        """)
    void softDeleteByAgentIdSourceRefPrefixAndVisibilityNotIn(@Param("agentId") Long agentId,
                                                              @Param("sourcePrefix") String sourcePrefix,
                                                              @Param("scope") String scope,
                                                              @Param("ownerKey") String ownerKey,
                                                              @Param("keepSet") List<String> keepSet,
                                                              @Param("now") LocalDateTime now);
}

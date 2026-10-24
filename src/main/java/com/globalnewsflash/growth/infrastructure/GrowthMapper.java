package com.globalnewsflash.growth.infrastructure;

import com.globalnewsflash.growth.domain.Distribution;
import com.globalnewsflash.growth.domain.JoinResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GrowthMapper {
    record Target(String targetId, String label, boolean synthetic, boolean authorized, long createdAt, long updatedAt) {}
    record Invite(String inviteKey, String targetId, boolean synthetic, long createdAt) {}

    @Select("SELECT target_id,label,synthetic,authorized,created_at,updated_at FROM growth_target WHERE target_id=#{targetId} FOR UPDATE")
    Target lockTarget(String targetId);

    @Insert("INSERT INTO growth_target(target_id,label,synthetic,authorized,created_at,updated_at) VALUES(#{targetId},#{label},TRUE,TRUE,#{now},#{now})")
    int insertTarget(@Param("targetId") String targetId, @Param("label") String label, @Param("now") long now);

    @Update("UPDATE growth_target SET authorized=#{authorized},updated_at=#{now} WHERE target_id=#{targetId} AND synthetic=TRUE")
    int setAuthorization(@Param("targetId") String targetId, @Param("authorized") boolean authorized, @Param("now") long now);

    @Select("SELECT operation_key,target_id,body,body_hash,status,synthetic,created_at FROM growth_distribution WHERE operation_key=#{operationKey}")
    Distribution distribution(String operationKey);

    @Insert("INSERT INTO growth_distribution(operation_key,target_id,body,body_hash,status,synthetic,created_at) VALUES(#{operationKey},#{targetId},#{body},#{bodyHash},#{status},#{synthetic},#{createdAt})")
    int insertDistribution(Distribution distribution);

    @Select("SELECT invite_key,target_id,synthetic,created_at FROM growth_invite WHERE invite_key=#{inviteKey}")
    Invite invite(String inviteKey);

    @Insert("INSERT INTO growth_invite(invite_key,target_id,synthetic,created_at) VALUES(#{inviteKey},#{targetId},TRUE,#{now})")
    int insertInvite(@Param("inviteKey") String inviteKey, @Param("targetId") String targetId, @Param("now") long now);

    @Select("SELECT update_id,invite_key,target_id,attribution,reason,synthetic,created_at FROM growth_join_event WHERE update_id=#{updateId}")
    JoinResult join(long updateId);

    @Insert("INSERT INTO growth_join_event(update_id,invite_key,target_id,attribution,reason,synthetic,created_at) VALUES(#{updateId},#{inviteKey},#{targetId},#{attribution},#{reason},#{synthetic},#{createdAt})")
    int insertJoin(JoinResult join);

    @Select("SELECT COUNT(*) FROM growth_join_event WHERE attribution='KNOWN'")
    long knownJoins();

    @Select("SELECT COUNT(*) FROM growth_join_event WHERE attribution='UNKNOWN'")
    long unknownJoins();

    @Select("SELECT COUNT(*) FROM growth_distribution")
    long distributionCount();
}

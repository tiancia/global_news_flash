package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.publication.domain.ChannelLimit;
import com.globalnewsflash.publication.domain.DeliveryAttempt;
import com.globalnewsflash.publication.domain.PublicationAlert;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.domain.PublicationResolution;
import com.globalnewsflash.publication.port.out.PublicationRepository;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PublicationMapper extends PublicationRepository {
    String COLUMNS = "id,event_key,event_version,target,kind,state,body,original_operation_id,"
            + "message_id,attempts,lease_token,lease_until,next_attempt_at,last_error,synthetic";

    @Select("SELECT " + COLUMNS + " FROM publication_operation WHERE id=#{id}")
    PublicationOperation find(String id);

    @Select("SELECT " + COLUMNS + " FROM publication_operation WHERE id=#{id} FOR UPDATE")
    PublicationOperation lockFind(String id);

    @Select("SELECT " + COLUMNS + " FROM publication_operation ORDER BY created_at,id")
    List<PublicationOperation> list();

    @Select("SELECT " + COLUMNS + " FROM publication_operation WHERE state IN ('PENDING','RETRY_WAIT') "
            + "AND (next_attempt_at IS NULL OR next_attempt_at<=#{now}) ORDER BY created_at,id LIMIT 20")
    List<PublicationOperation> due(Instant now);

    @Select("SELECT " + COLUMNS + " FROM publication_operation WHERE event_key=#{eventKey} "
            + "AND event_version=#{eventVersion} AND target=#{target} AND kind=#{kind} FOR UPDATE")
    PublicationOperation findKey(@Param("eventKey") String eventKey, @Param("eventVersion") int eventVersion,
            @Param("target") String target, @Param("kind") String kind);

    @Insert("INSERT INTO publication_operation (" + COLUMNS + ",created_at) VALUES "
            + "(#{operation.id},#{operation.eventKey},#{operation.eventVersion},#{operation.target},"
            + "#{operation.kind},#{operation.state},#{operation.body},#{operation.originalOperationId},"
            + "#{operation.messageId},#{operation.attempts},#{operation.leaseToken},#{operation.leaseUntil},"
            + "#{operation.nextAttemptAt},#{operation.lastError},#{operation.synthetic},#{createdAt})")
    int insert(@Param("operation") PublicationOperation operation, @Param("createdAt") Instant createdAt);

    @Select("SELECT paused FROM publication_control WHERE id=1 FOR UPDATE")
    boolean lockPaused();

    @Select("SELECT paused FROM publication_control WHERE id=1")
    boolean paused();

    @Update("UPDATE publication_control SET paused=#{paused} WHERE id=1")
    int setPaused(boolean paused);

    @Insert("INSERT INTO publication_control_audit (id,paused,changed_at) VALUES (#{id},#{paused},#{now})")
    int auditPause(@Param("id") String id, @Param("paused") boolean paused, @Param("now") Instant now);

    @Update("UPDATE publication_operation SET state='CLAIMED',lease_token=#{token},lease_until=#{until} "
            + "WHERE id=#{id} AND state IN ('PENDING','RETRY_WAIT') "
            + "AND (next_attempt_at IS NULL OR next_attempt_at<=#{now})")
    int claim(@Param("id") String id, @Param("token") String token, @Param("until") Instant until,
            @Param("now") Instant now);

    @Update("UPDATE publication_operation SET state='SENDING',attempts=attempts+1 "
            + "WHERE id=#{id} AND state='CLAIMED' AND lease_token=#{token} AND lease_until>#{now}")
    int beginSending(@Param("id") String id, @Param("token") String token, @Param("now") Instant now);

    @Update("UPDATE publication_operation SET state='PENDING',lease_token=NULL,lease_until=NULL "
            + "WHERE id=#{id} AND state='CLAIMED' AND lease_token=#{token}")
    int releaseClaim(@Param("id") String id, @Param("token") String token);

    @Update("UPDATE publication_operation SET state='RETRY_WAIT',next_attempt_at=#{retryAt},"
            + "last_error='PUBLICATION_CADENCE_WAIT',lease_token=NULL,lease_until=NULL "
            + "WHERE id=#{id} AND state='CLAIMED' AND lease_token=#{token}")
    int deferClaim(@Param("id") String id, @Param("token") String token, @Param("retryAt") Instant retryAt);

    @Select("SELECT target,hour_start,hour_count,day_start,day_count,next_allowed_at "
            + "FROM publication_channel_limit WHERE target=#{target} FOR UPDATE")
    ChannelLimit lockChannelLimit(String target);

    @Insert("INSERT INTO publication_channel_limit(target,hour_start,hour_count,day_start,day_count,next_allowed_at) "
            + "VALUES(#{target},#{hourStart},0,#{dayStart},0,#{now})")
    int insertChannelLimit(@Param("target") String target, @Param("hourStart") long hourStart,
            @Param("dayStart") long dayStart, @Param("now") Instant now);

    @Update("UPDATE publication_channel_limit SET hour_start=#{hourStart},hour_count=#{hourCount},"
            + "day_start=#{dayStart},day_count=#{dayCount},next_allowed_at=#{nextAllowedAt} WHERE target=#{target}")
    int saveChannelLimit(ChannelLimit limit);

    @Update("UPDATE publication_channel_limit SET next_allowed_at=CASE WHEN next_allowed_at<#{until} "
            + "THEN #{until} ELSE next_allowed_at END WHERE target=#{target}")
    int coolDownChannel(@Param("target") String target, @Param("until") Instant until);

    @Update("UPDATE publication_operation SET state='CANCELLED',last_error='ELIGIBILITY_REVOKED',"
            + "lease_token=NULL,lease_until=NULL WHERE id=#{id} AND state='CLAIMED' AND lease_token=#{token}")
    int cancelClaim(@Param("id") String id, @Param("token") String token);

    @Insert("INSERT INTO publication_attempt (id,operation_id,attempt_number,lease_token,started_at,outcome) "
            + "VALUES (#{id},#{operationId},#{attemptNumber},#{leaseToken},#{startedAt},'SENDING')")
    int insertAttempt(DeliveryAttempt attempt);

    @Update("UPDATE publication_operation SET state=#{state},message_id=#{messageId},"
            + "next_attempt_at=#{nextAttemptAt},last_error=#{error},lease_token=NULL,lease_until=NULL "
            + "WHERE id=#{id} AND state='SENDING' AND lease_token=#{token} AND lease_until>#{now}")
    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state,
            @Param("messageId") String messageId, @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error, @Param("now") Instant now);

    @Update("UPDATE publication_attempt SET finished_at=#{now},outcome=#{outcome},error=#{error} "
            + "WHERE operation_id=#{id} AND lease_token=#{token}")
    int finishAttempt(@Param("id") String id, @Param("token") String token,
            @Param("outcome") String outcome, @Param("error") String error, @Param("now") Instant now);

    @Select("SELECT " + COLUMNS + " FROM publication_operation "
            + "WHERE state IN ('CLAIMED','SENDING') AND lease_until<=#{now}")
    List<PublicationOperation> expired(Instant now);

    @Update("UPDATE publication_operation SET state=#{nextState},lease_token=NULL,lease_until=NULL,"
            + "last_error=#{error} WHERE id=#{id} AND state=#{oldState} AND lease_token=#{token} "
            + "AND lease_until<=#{now}")
    int recover(@Param("id") String id, @Param("oldState") String oldState,
            @Param("token") String token, @Param("nextState") String nextState,
            @Param("error") String error, @Param("now") Instant now);

    @Select("SELECT id,operation_id,attempt_number,lease_token,started_at,finished_at,outcome,error "
            + "FROM publication_attempt WHERE operation_id=#{id} ORDER BY attempt_number")
    List<DeliveryAttempt> attempts(String id);

    @Update("UPDATE publication_operation SET state=#{state},message_id=#{messageId},"
            + "last_error=#{reason},lease_token=NULL,lease_until=NULL,next_attempt_at=NULL "
            + "WHERE id=#{id} AND state='UNKNOWN'")
    int resolveUnknown(@Param("id") String id, @Param("state") String state,
            @Param("messageId") String messageId, @Param("reason") String reason);

    @Insert("INSERT INTO publication_resolution (id,operation_id,resolution,message_id,note,created_at) "
            + "VALUES (#{id},#{operationId},#{resolution},#{messageId},#{note},#{createdAt})")
    int insertResolution(PublicationResolution resolution);

    @Select("SELECT id,operation_id,resolution,message_id,note,created_at FROM publication_resolution "
            + "WHERE operation_id=#{id} ORDER BY created_at,id")
    List<PublicationResolution> resolutions(String id);

    @Insert("INSERT INTO publication_alert (id,operation_id,category,reason_code,created_at,acknowledged_at) "
            + "VALUES (#{id},#{operationId},#{category},#{reasonCode},#{createdAt},#{acknowledgedAt})")
    int insertAlert(PublicationAlert alert);

    @Select("SELECT id,operation_id,category,reason_code,created_at,acknowledged_at "
            + "FROM publication_alert ORDER BY created_at,id")
    List<PublicationAlert> alerts();
    @Select("SELECT a.id,a.operation_id,a.category,a.reason_code,a.created_at,a.acknowledged_at FROM publication_alert a "
            + "WHERE a.acknowledged_at IS NULL AND NOT EXISTS (SELECT 1 FROM ops_notification n WHERE n.id=a.id) "
            + "ORDER BY a.created_at,a.id LIMIT 100")
    List<PublicationAlert> unnotifiedAlerts();

    @Update("UPDATE publication_alert SET acknowledged_at=#{now} "
            + "WHERE operation_id=#{id} AND acknowledged_at IS NULL")
    int acknowledgeAlerts(@Param("id") String id, @Param("now") Instant now);
}

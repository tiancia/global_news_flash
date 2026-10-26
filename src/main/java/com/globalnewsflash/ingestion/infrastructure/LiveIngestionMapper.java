package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.automation.infrastructure.PipelineMapper.SourceRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LiveIngestionMapper {
    record SourceState(String sourceId, boolean registered, String cursorValue, String etag, String lastModified,
            Long lastAttemptAt, Long lastSuccessAt, long nextPollAt, int consecutiveFailures, int lastItemCount,
            String errorCode, String leaseOwner, long leaseUntil, String snapshotPayload, String snapshotEtag,
            String snapshotModified, int snapshotOffset, Long snapshotCreatedAt) {}
    record InboxRow(String id, String sourceId, String eventKey, String fingerprint, String materialPayload,
            String state, String detail, int attempts, long nextAttemptAt, String leaseOwner, long leaseUntil,
            long createdAt, Long completedAt) {}

    @Insert("""
            INSERT INTO ingestion_source_state(source_id,registered,next_poll_at,consecutive_failures,last_item_count,lease_until,snapshot_offset)
            VALUES(#{sourceId},FALSE,0,0,0,0,0)
            """)
    void createSourceState(String sourceId);

    @Select("SELECT * FROM ingestion_source_state WHERE source_id=#{sourceId} FOR UPDATE")
    SourceState lockSourceState(String sourceId);

    @Select("SELECT * FROM ingestion_source_state ORDER BY source_id")
    List<SourceState> sourceStates();

    @Select("SELECT * FROM news_source WHERE source_id=#{sourceId} AND synthetic=FALSE FOR UPDATE")
    SourceRow lockSource(String sourceId);

    @Select("SELECT * FROM news_source WHERE source_id=#{sourceId} AND synthetic=FALSE")
    SourceRow source(String sourceId);

    @Insert("""
            INSERT INTO news_source(id,source_id,enabled,approved,synthetic,license_version,permitted_producers,scope_description)
            VALUES(#{id},#{sourceId},#{enabled},#{approved},FALSE,#{licenseVersion},#{permittedProducers},#{scopeDescription})
            """)
    void insertSource(SourceRow source);

    @Update("""
            UPDATE news_source SET enabled=#{enabled},approved=#{approved},license_version=#{licenseVersion},
                permitted_producers=#{permittedProducers},scope_description=#{scopeDescription}
            WHERE source_id=#{sourceId} AND synthetic=FALSE
            """)
    void updateSource(SourceRow source);

    @Update("UPDATE ingestion_source_state SET registered=TRUE WHERE source_id=#{sourceId}")
    void registered(String sourceId);

    @Update("UPDATE news_source SET enabled=FALSE,approved=FALSE WHERE source_id=#{sourceId} AND synthetic=FALSE")
    void removeConfiguredSource(String sourceId);

    @Update("""
            UPDATE ingestion_source_state SET lease_owner=#{owner},lease_until=#{until},last_attempt_at=#{now}
            WHERE source_id=#{sourceId} AND lease_until<=#{now} AND next_poll_at<=#{now}
            """)
    int leaseSource(@Param("sourceId") String sourceId, @Param("owner") String owner,
                    @Param("until") long until, @Param("now") long now);

    @Update("""
            UPDATE ingestion_source_state SET snapshot_payload=#{payload},snapshot_etag=#{etag},snapshot_modified=#{modified},
                snapshot_offset=0,snapshot_created_at=#{createdAt}
            WHERE source_id=#{sourceId} AND lease_owner=#{owner} AND lease_until>#{now}
            """)
    int saveSnapshot(@Param("sourceId") String sourceId, @Param("owner") String owner, @Param("payload") String payload,
                     @Param("etag") String etag, @Param("modified") String modified,
                     @Param("createdAt") long createdAt, @Param("now") long now);

    @Update("""
            UPDATE ingestion_source_state SET snapshot_offset=#{offset},next_poll_at=#{next},last_success_at=#{now},
                consecutive_failures=0,last_item_count=#{count},error_code=NULL,lease_owner=NULL,lease_until=0
            WHERE source_id=#{sourceId} AND lease_owner=#{owner}
            """)
    int advanceSnapshot(@Param("sourceId") String sourceId, @Param("owner") String owner, @Param("offset") int offset,
                        @Param("next") long next, @Param("now") long now, @Param("count") int count);

    @Update("""
            UPDATE ingestion_source_state SET cursor_value=#{cursor},etag=#{etag},last_modified=#{modified},
                last_success_at=#{now},next_poll_at=#{next},consecutive_failures=0,last_item_count=#{count},
                error_code=NULL,lease_owner=NULL,lease_until=0,snapshot_payload=NULL,snapshot_etag=NULL,
                snapshot_modified=NULL,snapshot_offset=0,snapshot_created_at=NULL
            WHERE source_id=#{sourceId} AND lease_owner=#{owner}
            """)
    int finishSource(@Param("sourceId") String sourceId, @Param("owner") String owner, @Param("cursor") String cursor,
                     @Param("etag") String etag, @Param("modified") String modified, @Param("now") long now,
                     @Param("next") long next, @Param("count") int count);

    @Update("""
            UPDATE ingestion_source_state SET next_poll_at=#{next},consecutive_failures=consecutive_failures+1,
                error_code=#{code},lease_owner=NULL,lease_until=0 WHERE source_id=#{sourceId} AND lease_owner=#{owner}
            """)
    void failSource(@Param("sourceId") String sourceId, @Param("owner") String owner,
                    @Param("next") long next, @Param("code") String code);

    @Select("SELECT id FROM ingestion_inbox WHERE id=#{id}")
    String inboxId(String id);

    @Insert("""
            INSERT INTO ingestion_inbox(id,source_id,event_key,fingerprint,material_payload,state,detail,attempts,
                next_attempt_at,lease_until,created_at)
            VALUES(#{id},#{sourceId},#{eventKey},#{fingerprint},#{materialPayload},#{state},#{detail},0,#{createdAt},0,#{createdAt})
            """)
    void insertInbox(InboxRow row);

    @Select("SELECT COUNT(*) FROM ingestion_inbox WHERE state IN ('PENDING','RETRY','PROCESSING')")
    long pendingCount();

    @Select("""
            SELECT * FROM ingestion_inbox WHERE
                (state IN ('PENDING','RETRY') AND next_attempt_at<=#{now}) OR (state='PROCESSING' AND lease_until<=#{now})
            ORDER BY created_at,id LIMIT #{limit} FOR UPDATE
            """)
    List<InboxRow> lockDue(@Param("now") long now, @Param("limit") int limit);

    @Update("""
            UPDATE ingestion_inbox SET state='PROCESSING',attempts=attempts+1,lease_owner=#{owner},lease_until=#{until}
            WHERE id=#{id}
            """)
    void leaseInbox(@Param("id") String id, @Param("owner") String owner, @Param("until") long until);

    @Select("SELECT * FROM ingestion_inbox WHERE id=#{id} FOR UPDATE")
    InboxRow lockInbox(String id);

    @Update("""
            UPDATE ingestion_inbox SET state=#{state},detail=#{detail},completed_at=#{now},lease_owner=NULL,lease_until=0
            WHERE id=#{id} AND state='PROCESSING' AND lease_owner=#{owner}
            """)
    int complete(@Param("id") String id, @Param("owner") String owner, @Param("state") String state,
                 @Param("detail") String detail, @Param("now") long now);

    @Update("""
            UPDATE ingestion_inbox SET state='RETRY',detail=#{code},next_attempt_at=#{next},lease_owner=NULL,lease_until=0
            WHERE id=#{id} AND state='PROCESSING' AND lease_owner=#{owner}
            """)
    int retry(@Param("id") String id, @Param("owner") String owner, @Param("code") String code, @Param("next") long next);

    @Select("SELECT * FROM ingestion_inbox ORDER BY created_at DESC,id DESC LIMIT 100")
    List<InboxRow> recentInbox();

    @Update("""
            UPDATE ingestion_inbox SET state='PENDING',detail='OPERATOR_REQUEUED',next_attempt_at=#{now},
                lease_owner=NULL,lease_until=0,completed_at=NULL WHERE id=#{id}
            """)
    void requeue(@Param("id") String id, @Param("now") long now);
}

package com.globalnewsflash.digest.infrastructure;

import com.globalnewsflash.digest.domain.Candidate;
import com.globalnewsflash.digest.domain.DigestRecord;
import com.globalnewsflash.digest.port.out.DigestRepository;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DigestMapper extends DigestRepository {

    @Select("SELECT * FROM news_digest WHERE digest_date=#{date} AND target=#{target} FOR UPDATE")
    DigestRecord lockDate(@Param("date") String date, @Param("target") String target);
    @Select("SELECT * FROM news_digest WHERE publication_id=#{id}")
    DigestRecord byPublication(String id);
    @Select("SELECT * FROM news_digest WHERE event_key=#{key}")
    DigestRecord byEvent(String key);
    @Select("SELECT * FROM news_digest ORDER BY created_at DESC,id LIMIT 30")
    List<DigestRecord> list();
    @Insert("INSERT INTO news_digest(id,digest_date,target,event_key,publication_id,body,operation_ids_payload,"
            + "items_payload,window_start,window_end,created_at) VALUES(#{id},#{digestDate},#{target},#{eventKey},"
            + "#{publicationId},#{body},#{operationIdsPayload},#{itemsPayload},#{windowStart},#{windowEnd},#{createdAt})")
    int insert(DigestRecord record);
    @Update("UPDATE news_digest SET publication_id=#{publicationId} WHERE id=#{id} AND publication_id IS NULL")
    int attachPublication(@Param("id") String id, @Param("publicationId") String publicationId);

    @Select("""
            SELECT p.id AS operation_id,p.event_key,p.event_version,
              COALESCE((SELECT MAX(a.finished_at) FROM publication_attempt a
                        WHERE a.operation_id=p.id AND a.outcome='SUCCESS'),
                       (SELECT MAX(r.created_at) FROM publication_resolution r
                        WHERE r.operation_id=p.id AND r.resolution='CONFIRMED_SENT')) AS sent_at
            FROM publication_operation p
            WHERE p.target=#{target} AND p.state='SENT' AND p.synthetic=FALSE
              AND p.event_key NOT LIKE 'digest:%'
              AND (EXISTS (SELECT 1 FROM publication_attempt a WHERE a.operation_id=p.id AND a.outcome='SUCCESS'
                           AND a.finished_at>=#{since} AND a.finished_at<=#{now})
                   OR EXISTS (SELECT 1 FROM publication_resolution r WHERE r.operation_id=p.id
                              AND r.resolution='CONFIRMED_SENT' AND r.created_at>=#{since} AND r.created_at<=#{now}))
            ORDER BY sent_at DESC,p.event_key,p.event_version DESC LIMIT 500
            """)
    List<Candidate> candidates(@Param("target") String target, @Param("since") Instant since, @Param("now") Instant now);

    @Select("""
            SELECT COALESCE((SELECT MAX(a.finished_at) FROM publication_attempt a
                             WHERE a.operation_id=#{id} AND a.outcome='SUCCESS'),
                            (SELECT MAX(r.created_at) FROM publication_resolution r
                             WHERE r.operation_id=#{id} AND r.resolution='CONFIRMED_SENT'))
            """)
    Instant sentAt(String id);
}

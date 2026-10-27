package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.port.out.SourceRegistry;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceRegistryMapper extends SourceRegistry {
    @Select("SELECT * FROM news_source ORDER BY id")
    List<SourceRegistration> sources();

    @Select("SELECT * FROM news_source ORDER BY id FOR UPDATE")
    List<SourceRegistration> lockSources();

    @Update("UPDATE news_source SET enabled=#{enabled} WHERE id=#{id}")
    int enableSource(@Param("id") String id, @Param("enabled") boolean enabled);
}

package com.datastax.samples;

import static com.datastax.samples.ExampleUtils.closeSessionAndCluster;
import static com.datastax.samples.ExampleUtils.createKeyspace;
import static com.datastax.samples.ExampleUtils.createTableVideo;
import static com.datastax.samples.ExampleUtils.createUdtVideoFormat;
import static com.datastax.samples.ExampleUtils.truncateTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.CodecRegistry;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TypeCodec;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.utils.UUIDs;
import com.datastax.samples.codec.VideoFormatDtoCodec;
import com.datastax.samples.dto.VideoDto;
import com.datastax.samples.dto.VideoFormatDto;

/**
 * Working on advance types LIST, SET, MAP and UDT.
 * 
 * This is the table we work with:
 *
 * CREATE TABLE IF NOT EXISTS videos (
 *   videoid    uuid,
 *   title      text,
 *   upload    timestamp,
 *   email      text,
 *   url        text,
 *   tags       set <text>,
 *   frames     list<int>,
 *   formats    map <text,frozen<video_format>>,
 *   PRIMARY KEY (videoid)
 * );
 * 
 *  using the simple User Defined Type (UDT) video_format
 *  
 * CREATE TYPE IF NOT EXISTS video_format (
 *   width   int,
 *   height  int
 * );
 * 
 * We want to :
 * - Add a new record with Query Builder
 * - Add a new tag (SET) in existing record
 * - Remove a new tag (SET) in existing record
 * - Add a new tag (SET) in existing record
 * - Remove a new tag (SET) in existing record
 * 
 * Need Help ? Join us on community.datastax.com to ask your questions for free.
 */
public class SampleCode3x_CRUD_04_ListSetMapAndUdt implements ExampleSchema {
	
    /** Logger for the class. */
	private static Logger LOGGER = LoggerFactory.getLogger(SampleCode3x_CRUD_04_ListSetMapAndUdt.class);
    
	// This will be used as singletons for the sample
    private static Cluster  cluster;
    private static Session  session;
    private static UserType videoFormatUdt;
    
    // Prepare your statements once and execute multiple times 
    private static PreparedStatement stmtCreateVideo;
    private static PreparedStatement stmtReadVideoTags;
    
    /** StandAlone (vs JUNIT) to help you running. */
    public static void main(String[] args) {
        try {
            
            // Create killrvideo keyspace (if needed)
            createKeyspace();

            // Initialize Cluster and Session Objects 
            session = connect(cluster);
            
            // Create table
            createUdtVideoFormat(session);
            createTableVideo(session);
            
            // Empty tables for tests
            truncateTable(session, VIDEO_TABLENAME);
            
            // Prepare your statements once and execute multiple times 
            prepareStatements();
            
            // ========= CREATE ============
            
            UUID myVideoId = UUIDs.random();
            
            // Dto wrapping all data but no object Mapping here, QueryBuilder only
            VideoDto dto = new VideoDto();
            dto.setVideoid(myVideoId);
            dto.setTitle("The World’s Largest Apache Cassandra™ NoSQL Event | DataStax Accelerate 2020");
            dto.setUrl("https://www.youtube.com/watch?v=7afxKEH7t8Q");
            dto.setEmail("clun@sample.com");
            dto.getTags().add("cassandra");
            dto.getFrames().addAll(Arrays.asList(2, 3, 5, 8, 13, 21));
            dto.getTags().add("accelerate");
            dto.getFormats().put("mp4", new VideoFormatDto(640, 480));
            dto.getFormats().put("ogg", new VideoFormatDto(640, 480));
            createVideo(dto);
            
            // Operations on SET (add/remove)
            LOGGER.info("+ Tags before adding 'OK' {}", listTagsOnVideo(myVideoId));
            addTagToVideo(myVideoId,  "OK");
            LOGGER.info("+ Tags after adding 'OK' {}", listTagsOnVideo(myVideoId));
            removeTagFromVideo(myVideoId,  "accelerate");
            LOGGER.info("+ Tags after removing 'accelerate' {}", listTagsOnVideo(myVideoId));
            
            // Operations on MAP (add/remove)
            LOGGER.info("+ Formats before {}", listFormatsOnVideo(myVideoId));
            addFormatToVideo(myVideoId, "hd", new VideoFormatDto(1920, 1080));
            LOGGER.info("+ Formats after adding 'mkv' {}", listFormatsOnVideo(myVideoId));
            removeFormatFromVideo(myVideoId, "ogg");
            LOGGER.info("+ Formats after removing 'ogg' {}", listFormatsOnVideo(myVideoId));
            LOGGER.info("+ Formats after removing 'ogg' {}", listFormatsOnVideoWithCustomCodec(myVideoId));
            
            // Operations on LIST (replaceAll, append, replace one
            LOGGER.info("+ Formats frames before {}", listFramesOnVideo(myVideoId));
            updateAllFrames(myVideoId, Arrays.asList(1,2,3));
            LOGGER.info("+ Formats frames after update all {}", listFramesOnVideo(myVideoId));
            appendFrame(myVideoId, 4);
            LOGGER.info("+ Formats frames after append 4 {}", listFramesOnVideo(myVideoId));
            updateOneFrame(myVideoId, 1, 128);
            LOGGER.info("+ Formats frames after changing idx=1 per 128 {}", listFramesOnVideo(myVideoId));
            
        } finally {
            // Close Cluster and Session 
            closeSessionAndCluster(session, cluster);
        }
        System.exit(0);
    }
    
    private static void createVideo(VideoDto dto) {
        
        Map<String, UDTValue> formats = new HashMap<>();
        if (null != dto.getFormats()) {
            for (Map.Entry<String, VideoFormatDto> dtoEntry : dto.getFormats().entrySet()) {
                formats.put(dtoEntry.getKey(), videoFormatUdt.newValue()
                        .setInt(UDT_VIDEO_FORMAT_WIDTH,  dtoEntry.getValue().getWidth())
                        .setInt(UDT_VIDEO_FORMAT_HEIGHT, dtoEntry.getValue().getHeight()));
            }
        }
        session.execute(stmtCreateVideo.bind()
                .setUUID(VIDEO_VIDEOID, dto.getVideoid())
                .setString(VIDEO_TITLE, dto.getTitle())
                .setString(VIDEO_USER_EMAIL, dto.getEmail())
                .setTimestamp(VIDEO_UPLOAD, new Date(dto.getUpload()))
                .setString(VIDEO_URL, dto.getUrl())
                .setSet(VIDEO_TAGS, dto.getTags())
                .setList(VIDEO_FRAMES, dto.getFrames())
                //.setMap(VIDEO_FORMAT, formats)          // this is default behaviour with UDT
                .setMap(VIDEO_FORMAT, dto.getFormats())); // this is possible thank to the VideoFor
    }
    
    // SET
    
    private static void addTagToVideo(UUID videoId, String newTag) {
       // Note that this statement is not prepared, not supported for add
       session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                .with(QueryBuilder.add(VIDEO_TAGS, newTag))
                .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    private static void removeTagFromVideo(UUID videoId, String oldTag) {
        // Note that this statement is not prepared, not supported for add
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                 .with(QueryBuilder.remove(VIDEO_TAGS, oldTag))
                 .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    // LIST
    private static void updateAllFrames(UUID videoId, List<Integer> values) {
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                .with(QueryBuilder.set(VIDEO_FRAMES, values))
                .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    private static void appendFrame(UUID videoId, Integer lastItem) {
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                .with(QueryBuilder.append(VIDEO_FRAMES, lastItem))
                .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    private static void updateOneFrame(UUID videoId, int idx, Integer item) {
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                .with(QueryBuilder.setIdx(VIDEO_FRAMES, idx, item))
                .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    // UDT
    
    private static void addFormatToVideo(UUID videoId, String key, VideoFormatDto format) {
        // Note that this statement is not prepared, not supported for add
        UDTValue udType = videoFormatUdt.newValue()
                .setInt(UDT_VIDEO_FORMAT_WIDTH,  format.getWidth())
                .setInt(UDT_VIDEO_FORMAT_HEIGHT, format.getHeight());
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                 .with(QueryBuilder.put(VIDEO_FORMAT, key, udType))
                 .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    private static void removeFormatFromVideo(UUID videoId, String key) {
        session.execute(QueryBuilder.update(VIDEO_TABLENAME)
                 .with(QueryBuilder.remove(VIDEO_FORMAT, key))
                 .where(QueryBuilder.eq(VIDEO_VIDEOID, videoId)));
    }
    
    private static Set < String > listTagsOnVideo(UUID videoid) {
        Row row = session.execute(stmtReadVideoTags.bind(videoid)).one();
        return (null == row)  ? new HashSet<>() : row.getSet(VIDEO_TAGS, String.class);
    }
    
    private static List < Integer > listFramesOnVideo(UUID videoid) {
        Row row = session.execute(stmtReadVideoTags.bind(videoid)).one();
        return (null == row)  ? new ArrayList<>() : row.getList(VIDEO_FRAMES, Integer.class);
    }
    
    private static Map < String, VideoFormatDto > listFormatsOnVideo(UUID videoId) {
        Map < String, VideoFormatDto > mapOfFormats = new HashMap<>();
        Row row = session.execute(stmtReadVideoTags.bind(videoId)).one();
        if (null != row) {
            Map < String, UDTValue> myMap = row.getMap(VIDEO_FORMAT, String.class, UDTValue.class);
            for (Map.Entry<String, UDTValue> entry : myMap.entrySet()) {
                mapOfFormats.put(entry.getKey(), new VideoFormatDto(
                        entry.getValue().getInt(UDT_VIDEO_FORMAT_WIDTH),
                        entry.getValue().getInt(UDT_VIDEO_FORMAT_HEIGHT)));
            }
        }
        return mapOfFormats;
    }

    private static Map < String, VideoFormatDto > listFormatsOnVideoWithCustomCodec(UUID videoId) {
        Map < String, VideoFormatDto > mapOfFormats = new HashMap<>();
        Row row = session.execute(stmtReadVideoTags.bind(videoId)).one();
        if (null != row) {
            return row.getMap(VIDEO_FORMAT, String.class, VideoFormatDto.class);
        }
        return mapOfFormats;
    }
    
    public static Session connect(Cluster cluster) {
        CodecRegistry codecRegistry = new CodecRegistry();
        cluster = Cluster.builder().addContactPoint("127.0.0.1")
                         .withCodecRegistry(codecRegistry).build();
        LOGGER.info("Connected to Cluster, Looking for keyspace '{}'...", KEYSPACE_NAME);
        session        = cluster.connect(KEYSPACE_NAME);
        LOGGER.info("[OK] Connected to Keyspace");
        videoFormatUdt = cluster.getMetadata().getKeyspace(KEYSPACE_NAME)
                                .getUserType(UDT_VIDEO_FORMAT_NAME);
        
        // If we want to easy mapping with DTO, create a dedicated CODEC
        TypeCodec<UDTValue>          tc = codecRegistry.codecFor(videoFormatUdt);
        TypeCodec<VideoFormatDto> codec = new VideoFormatDtoCodec(tc, VideoFormatDto.class);
        codecRegistry.register(codec);
        
        LOGGER.info("[OK] UserDefinedType retrieved {}", videoFormatUdt.getTypeName());
        return session;
    }
    
    
    private static void prepareStatements() {
        stmtCreateVideo = session.prepare(QueryBuilder.insertInto(VIDEO_TABLENAME)
                .value(VIDEO_VIDEOID,    QueryBuilder.bindMarker(VIDEO_VIDEOID))
                .value(VIDEO_TITLE,      QueryBuilder.bindMarker(VIDEO_TITLE))
                .value(VIDEO_USER_EMAIL, QueryBuilder.bindMarker(VIDEO_USER_EMAIL))
                .value(VIDEO_UPLOAD,     QueryBuilder.bindMarker(VIDEO_UPLOAD))
                .value(VIDEO_URL,        QueryBuilder.bindMarker(VIDEO_URL))
                .value(VIDEO_TAGS,       QueryBuilder.bindMarker(VIDEO_TAGS))
                .value(VIDEO_FRAMES,     QueryBuilder.bindMarker(VIDEO_FRAMES))
                .value(VIDEO_FORMAT,     QueryBuilder.bindMarker(VIDEO_FORMAT)));
        stmtReadVideoTags = session.prepare(QueryBuilder
                .select()
                .column(VIDEO_TAGS).column(VIDEO_FORMAT).column(VIDEO_FRAMES)
                .from(VIDEO_TABLENAME)
                .where(QueryBuilder.eq(VIDEO_VIDEOID, QueryBuilder.bindMarker())));
    }
    
}

package xyz.joshefin.memos.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class GoogleKeepImporter implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(GoogleKeepImporter.class);

	@Autowired
	private ObjectMapper objectMapper;

	private TransactionTemplate transactionTemplate;

	private JdbcClient jdbcClient;

	@Override
	public void run(ApplicationArguments args) throws Exception {
		args.getOptionNames().forEach(option ->
				log.info("Arg: {} --> {}", option, args.getOptionValues(option)));

		Path sourceDirectory = getPathArgument("source-dir", args);

		String databaseUrl = getStringArgument("db-url", args);
		String databasePort = getStringArgument("db-port", args);
		String databaseName = getStringArgument("db-name", args);
		String databaseUsername = getStringArgument("db-username", args);
		String databasePassword = getStringArgument("db-password", args);

		Long memosUserId = getLongArgument("memos-user", args);
		Path memosDirectory = getPathArgument("memos-dir", args);
		String memosResourcesPath = getStringArgument("memos-resources-path", args);

		DataSource dataSource = DataSourceBuilder.create()
				.url("jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&useTimezone=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
						.formatted(databaseUrl, databasePort, databaseName))
				.username(databaseUsername)
				.password(databasePassword)
				.build();

		transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));

		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		NamedParameterJdbcTemplate namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);

		jdbcClient = JdbcClient.create(namedParameterJdbcTemplate);

		log.info("Directory: {}", sourceDirectory);

		AtomicLong fileCounter = new AtomicLong(0L);
		AtomicLong memoCounter = new AtomicLong(0L);

		try (Stream<Path> files = Files.list(sourceDirectory)) {
			files.filter(file -> file.toString().toLowerCase().endsWith(".json")).forEach(file -> {
				log.info("Processing file: {}", file.getFileName());

				fileCounter.incrementAndGet();

				byte[] bytes = new byte[0];

				try {
					bytes = Files.readAllBytes(file);
				}
				catch (IOException e) {
					log.error("Failed to read file {}: {}", file.getFileName(), e.getMessage());
				}

				if (bytes.length > 0) {
					Note note = null;

					try {
						note = objectMapper.readValue(bytes, Note.class);
					}
					catch (IOException e) {
						log.error("Failed to parse file {}: {}", file.getFileName(), e.getMessage());
					}

					if (note != null) {
						if (!note.isTrashed()) {
							Instant createdInstant = Instant.EPOCH.plus(note.createdTimestampUsec(), ChronoUnit.MICROS);
							Instant updatedInstant = Instant.EPOCH.plus(note.userEditedTimestampUsec(), ChronoUnit.MICROS);

							Map<String, Object> data = new HashMap<>();

							data.put("uid", Utils.randomToken());

							data.put("creator_id", memosUserId);
							data.put("created_ts", createdInstant);
							data.put("updated_ts", updatedInstant);
							data.put("row_status", note.isArchived() ? "ARCHIVED" : "NORMAL");

							StringJoiner contentJoiner = new StringJoiner("\n");

							if (note.title() != null && !note.title().isBlank())
								contentJoiner.add("# " + note.title());

							if (note.textContent() != null && !note.textContent().isBlank())
								contentJoiner.add(note.textContent());

							if (note.listContent() != null && !note.listContent().isEmpty()) {
								contentJoiner.add(note.listContent()
										.stream()
										.filter(entry -> !entry.text().isBlank())
										.map(entry -> {
											StringBuilder entryBuilder = new StringBuilder();

											entryBuilder.append("- [");
											if (entry.isChecked())
												entryBuilder.append("x");
											else
												entryBuilder.append(" ");
											entryBuilder.append("] ");
											entryBuilder.append(entry.text());

											return entryBuilder.toString();
										})
										.collect(Collectors.joining("\n")));
							}

							if (note.labels() != null) {
								contentJoiner.add(note.labels()
										.stream()
										.map(label -> "#" + label.name())
										.collect(Collectors.joining(" ")));
							}

							data.put("content", contentJoiner.toString());

							data.put("visibility", "PRIVATE");

							data.put("tags", "[]");

							Map<String, Object> payload = new HashMap<>();

							if (note.listContent() != null) {
								payload.put("hasTaskList", true);
								payload.put("hasIncompleteTasks", note.listContent().stream().anyMatch(e -> !e.isChecked()));
							}

							if (note.labels() != null)
								payload.put("tags", note.labels().stream().map(NoteLabel::name).toList());

							data.put("payload", convertToJson(Map.of("property", payload)));

							log.trace("Memo data: {}", data);

							boolean pinned = note.isPinned();

							Long id = transactionTemplate.execute(transactionStatus -> {
								GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();

								jdbcClient.sql("""
												insert into memo
												(uid, creator_id, created_ts, updated_ts, row_status, content, visibility, tags, payload)
												values
												(:uid, :creator_id, :created_ts, :updated_ts, :row_status, :content, :visibility, :tags, :payload)
												""")
										.params(data)
										.update(keyHolder);

								long memoId = keyHolder.getKey().longValue();

								log.info("Created memo: {}", memoId);

								memoCounter.incrementAndGet();

								if (pinned) {
									jdbcClient.sql("""
													insert into memo_organizer
													(memo_id, user_id, pinned)
													values
													(:memo_id, :user_id, :pinned)
													""")
											.param("memo_id", memoId)
											.param("user_id", memosUserId)
											.param("pinned", 1)
											.update();
								}

								return memoId;
							});

							if (id != null) {
								if (note.attachments() != null) {
									Path resourcesDirectory = memosDirectory.resolve(memosResourcesPath);

									note.attachments().forEach(attachment -> {
										Path attachmentFile = sourceDirectory.resolve(attachment.filePath());

										try {
											long attachmentSize = Files.size(attachmentFile);

											if (attachmentSize > 0L) {
												String targetFileName = id + "-" + attachmentFile.getFileName().toString();

												Files.copy(attachmentFile, resourcesDirectory.resolve(targetFileName));

												transactionTemplate.executeWithoutResult(transactionStatus -> {
													Map<String, Object> attachmentData = new HashMap<>();

													attachmentData.put("uid", Utils.randomToken());
													attachmentData.put("creator_id", memosUserId);
													attachmentData.put("created_ts", createdInstant);
													attachmentData.put("updated_ts", updatedInstant);
													attachmentData.put("filename", attachmentFile.getFileName().toString());
													attachmentData.put("type", attachment.mimetype());
													attachmentData.put("size", attachmentSize);
													attachmentData.put("memo_id", id);
													attachmentData.put("storage_type", "LOCAL");
													attachmentData.put("reference", Path.of(memosResourcesPath, targetFileName).toString());
													attachmentData.put("payload", "{}");

													jdbcClient.sql("""
																	insert into resource
																	(uid, creator_id, created_ts, updated_ts, filename, type, size, memo_id, storage_type, reference, payload)
																	values
																	(:uid, :creator_id, :created_ts, :updated_ts, :filename, :type, :size, :memo_id, :storage_type, :reference, :payload)
																	""")
															.params(attachmentData)
															.update();

													log.info("Stored resource {} for memo {}.", targetFileName, id);
												});
											}
										}
										catch (IOException e) {
											log.error("Failed to read file {}. {}", attachmentFile.getFileName(), e.getMessage());
										}
									});
								}
							}
						}
						else
							log.warn("Skipping trashed note {}.", file.getFileName());
					}
					else
						log.warn("Failed to read file {}.", file.getFileName());
				}
				else
					log.warn("File {} is empty.", file.getFileName());
			});
		}

		log.info("Imported {}/{}.", memoCounter.get(), fileCounter.get());
	}

	private String getStringArgument(String name, ApplicationArguments args) {
		return args.getOptionValues(name).getFirst();
	}

	private Long getLongArgument(String name, ApplicationArguments args) {
		return Long.parseLong(args.getOptionValues(name).getFirst());
	}

	private Path getPathArgument(String name, ApplicationArguments args) {
		return Path.of(args.getOptionValues(name).getFirst());
	}

	private String convertToJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			log.error("Failed to serialize value as json. {}", e.getMessage());
		}

		return null;
	}

	private record Note(
			String color,
			boolean isTrashed,
			boolean isPinned,
			boolean isArchived,
			String textContent,
			String title,
			long userEditedTimestampUsec,
			long createdTimestampUsec,
			List<NoteLabel> labels,
			List<ListEntry> listContent,
			List<Attachment> attachments) {}

	private record NoteLabel(String name) {}

	private record ListEntry(String text, boolean isChecked) {}

	private record Attachment(String filePath, String mimetype) {}
}

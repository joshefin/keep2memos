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

		String directoryPath = args.getOptionValues("dir").getFirst();

		String databaseUrl = args.getOptionValues("db-url").getFirst();
		String databasePort = args.getOptionValues("db-port").getFirst();
		String databaseName = args.getOptionValues("db-name").getFirst();
		String databaseUsername = args.getOptionValues("db-username").getFirst();
		String databasePassword = args.getOptionValues("db-password").getFirst();

		Long userId = Long.valueOf(args.getOptionValues("memos-user").getFirst());

		// DataSourceTransactionManagerAutoConfiguration

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

		Path directory = Path.of(directoryPath);

		log.info("Directory: {}", directory);

		try (Stream<Path> files = Files.list(directory)) {
			files.filter(file -> file.toString().toLowerCase().endsWith(".json")).forEach(file -> {
				log.info("Processing file: {}", file.getFileName());

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
							Map<String, Object> data = new HashMap<>();

							data.put("uid", Utils.randomToken());

							data.put("creator_id", userId);
							data.put("created_ts", Instant.EPOCH.plus(note.createdTimestampUsec(), ChronoUnit.MICROS));
							data.put("updated_ts", Instant.EPOCH.plus(note.userEditedTimestampUsec(), ChronoUnit.MICROS));
							data.put("row_status", note.isArchived() ? "ARCHIVED" : "NORMAL");

							StringBuilder contentBuilder = new StringBuilder();

							if (note.title() != null && !note.title().isBlank()) {
								contentBuilder.append("# ");
								contentBuilder.append(note.title());
								contentBuilder.append("\n");
							}

							if (note.textContent() != null && !note.textContent().isBlank())
								contentBuilder.append(note.textContent());

							if (note.listContent() != null && !note.listContent().isEmpty()) {
								note.listContent().forEach(entry -> {
									contentBuilder.append("- [");
									if (entry.isChecked())
										contentBuilder.append("x");
									else
										contentBuilder.append(" ");
									contentBuilder.append("] ");
									contentBuilder.append(entry.text());
									contentBuilder.append("\n");
								});
							}

							data.put("content", contentBuilder.toString());

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

							boolean pinned = note.isPinned();

							transactionTemplate.executeWithoutResult(transactionStatus -> {
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

								if (pinned) {
									jdbcClient.sql("""
													insert into memo_organizer
													(memo_id, user_id, pinned)
													values
													(:memo_id, :user_id, :pinned)
													""")
											.param("memo_id", memoId)
											.param("user_id", userId)
											.param("pinned", 1)
											.update();
								}
							});
						}
					}
				}
			});
		}

		log.info("Completed.");
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
			List<ListEntry> listContent) {}

	private record NoteLabel(String name) {}

	private record ListEntry(String text, boolean isChecked) {}
}

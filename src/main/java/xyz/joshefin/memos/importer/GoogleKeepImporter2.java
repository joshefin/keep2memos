package xyz.joshefin.memos.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class GoogleKeepImporter2 implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(GoogleKeepImporter2.class);

	@Autowired
	private ObjectMapper objectMapper;

	@Override
	public void run(ApplicationArguments args) throws Exception {
		args.getOptionNames().forEach(option ->
				log.info("Arg: {} --> {}", option, args.getOptionValues(option)));

		Path keepDirectory = getPathArgument("keep-dir", args);

		String memosUrl = getStringArgument("memos-url", args);
		String memosAccessToken = getStringArgument("memos-token", args);

		AtomicLong fileCounter = new AtomicLong(0L);
		AtomicLong memoCounter = new AtomicLong(0L);

		try (HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(30L))
				.build()) {

			try (Stream<Path> files = Files.list(keepDirectory)) {
				files.filter(file -> file.toString().toLowerCase().endsWith(".json"))
						.forEach(file -> {
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

										Map<String, Object> createMemoData = new HashMap<>();

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

										//createMemoData.put("state", "NORMAL");
										createMemoData.put("content", contentJoiner.toString());
										createMemoData.put("visibility", "PRIVATE");

										// TODO izgleda da ne treba
									/*
									if (note.listContent() != null) {
										data.put("property", Map.of(
												"hasTaskList", true,
												"hasIncompleteTasks", note.listContent().stream().anyMatch(e -> !e.isChecked())));
									}
									*/

										// log.trace("Memo data: {}", data);

										String createMemoJson = convertToJson(createMemoData);

										log.trace("Create memo JSON: {}", createMemoJson);

										if (createMemoJson != null) {
											try {
												HttpResponse<String> createMemoResponse = httpClient.send(HttpRequest.newBuilder()
																.uri(URI.create(memosUrl).resolve("/api/v1/memos"))
																.timeout(Duration.ofSeconds(30L))
																.header("Authorization", "Bearer " + memosAccessToken)
																.POST(HttpRequest.BodyPublishers.ofString(createMemoJson, StandardCharsets.UTF_8))
																.build(),
														HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

												if (createMemoResponse.statusCode() == 200) {
													Map<String, Object> createMemoResponseData = convertToMap(createMemoResponse.body());

													log.info("Created memo {}.", createMemoResponseData.get("name"));

													memoCounter.incrementAndGet();

													Map<String, Object> updateMemoData = new HashMap<>();

													updateMemoData.put("createTime", createdInstant);
													updateMemoData.put("updateTime", updatedInstant);
													updateMemoData.put("pinned", note.isPinned());

													String updateMemoJson = convertToJson(updateMemoData);

													log.trace("Update memo JSON: {}", updateMemoJson);

													if (updateMemoJson != null) {
														HttpResponse<String> updateMemoResponse = httpClient.send(HttpRequest.newBuilder()
																		.uri(URI.create(memosUrl).resolve("/api/v1/" + createMemoResponseData.get("name")))
																		.timeout(Duration.ofSeconds(30L))
																		.header("Authorization", "Bearer " + memosAccessToken)
																		.method("PATCH", HttpRequest.BodyPublishers.ofString(updateMemoJson, StandardCharsets.UTF_8))
																		.build(),
																HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

														if (updateMemoResponse.statusCode() == 200) {
															log.info("Updated memo {}.", createMemoResponseData.get("name"));
														}
														else
															log.error("Failed to update memo. Response: {} - {}",
																	updateMemoResponse.statusCode(),
																	updateMemoResponse.body().lines().collect(Collectors.joining(" | ")));
													}

													if (note.attachments() != null) {
														List<Map<String, Object>> resources = note.attachments()
																.stream()
																.map(attachment -> {
																	Map<String, Object> resource = new HashMap<>();

																	Path attachmentFile = keepDirectory.resolve(attachment.filePath());

																	try {
																		long attachmentSize = Files.size(attachmentFile);

																		if (attachmentSize <= 0L)
																			return null;

																		resource.put("memo", createMemoResponseData.get("name"));
																		resource.put("filename", attachmentFile.getFileName().toString());
																		resource.put("content", Base64.getEncoder().encodeToString(Files.readAllBytes(attachmentFile)));
																		resource.put("type", attachment.mimetype());
																		resource.put("size", attachmentSize);
																	}
																	catch (IOException e) {
																		log.error("Failed to read file {}. {}", attachmentFile.getFileName(), e.getMessage());

																		return null;
																	}

																	return resource;
																})
																.filter(Objects::nonNull)
																.toList();

														if (!resources.isEmpty()) {
															resources.forEach(resourceData -> {
																String resourceJson = convertToJson(resourceData);

																if (resourceJson != null) {
																	try {
																		HttpResponse<String> resourceResponse = httpClient.send(HttpRequest.newBuilder()
																						.uri(URI.create(memosUrl).resolve("/api/v1/resources"))
																						.timeout(Duration.ofSeconds(30L))
																						.header("Authorization", "Bearer " + memosAccessToken)
																						.POST(HttpRequest.BodyPublishers.ofString(resourceJson, StandardCharsets.UTF_8))
																						.build(),
																				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

																		if (resourceResponse.statusCode() == 200) {
																			log.info("Created resource.");
																		}
																		else {
																			log.error("Failed to create resource. Response: {} - {}",
																					resourceResponse.statusCode(),
																					resourceResponse.body().lines().collect(Collectors.joining(" | ")));
																		}
																	}
																	catch (IOException | InterruptedException e) {
																		log.error("Failed to create resource. {}", e.getMessage(), e);
																	}
																}
															});
														}
													}
												}
												else
													log.error("Failed to create memo. Response: {} - {}",
															createMemoResponse.statusCode(),
															createMemoResponse.body().lines().collect(Collectors.joining(" | ")));
											}
											catch (IOException | InterruptedException e) {
												log.error("Failed to create memo. {}", e.getMessage(), e);
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
		}

		log.info("Imported {}/{}.", memoCounter.get(), fileCounter.get());
	}

	private String getStringArgument(String name, ApplicationArguments args) {
		return args.getOptionValues(name).getFirst();
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

	private Map<String, Object> convertToMap(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<>() {});
		}
		catch (JsonProcessingException e) {
			log.error("Failed to deserialize json. {}", e.getMessage());
		}

		return Collections.emptyMap();
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

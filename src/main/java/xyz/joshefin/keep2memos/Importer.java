package xyz.joshefin.keep2memos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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

public class Importer {

	private static final String KEEP_DIR_OPTION = "keep-dir";
	private static final String MEMOS_URL_OPTION = "memos-url";
	private static final String MEMOS_TOKEN_OPTION = "memos-token";

	private final ObjectMapper objectMapper;

	public Importer() {
		objectMapper = new ObjectMapper();

		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		objectMapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
		objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

		objectMapper.registerModule(new JavaTimeModule());
	}

	public static void main(String[] args) {
		Importer importer = new Importer();

		try {
			importer.run(args);
		}
		catch (Exception e) {
			System.err.println(e.getMessage());
		}
	}

	public void run(String[] args) throws Exception {
		Map<String, String> params = parseParams(args);

		if (!Stream.of(KEEP_DIR_OPTION, MEMOS_URL_OPTION, MEMOS_TOKEN_OPTION).allMatch(option -> {
			if (!params.containsKey(option)) {
				System.err.println("Missing '" + option + "' option.");
				return false;
			}
			else if (params.getOrDefault(option, "").isBlank()) {
				System.err.println("Option '" + option + "' is invalid.");
				return false;
			}

			return true;
		})) {
			return;
		}

		Path keepDirectory = Path.of(params.get(KEEP_DIR_OPTION));

		String memosUrl = params.get(MEMOS_URL_OPTION);
		String memosAccessToken = params.get(MEMOS_TOKEN_OPTION);

		AtomicLong fileCounter = new AtomicLong(0L);
		AtomicLong successCounter = new AtomicLong(0L);
		AtomicLong failCounter = new AtomicLong(0L);
		AtomicLong skipCounter = new AtomicLong(0L);

		try (HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(30L))
				.build()) {

			try (Stream<Path> files = Files.list(keepDirectory)) {
				files.filter(file -> file.toString().toLowerCase().endsWith(".json"))
						.forEach(file -> {
							System.out.println("Processing file: " + file.getFileName());

							fileCounter.incrementAndGet();

							byte[] bytes = new byte[0];

							try {
								bytes = Files.readAllBytes(file);
							}
							catch (IOException e) {
								System.err.printf("Failed to read file %s: %s%n", file.getFileName(), e.getMessage());
							}

							if (bytes.length > 0) {
								Note note = null;

								try {
									note = objectMapper.readValue(bytes, Note.class);
								}
								catch (IOException e) {
									System.err.printf("Failed to parse file %s: %s%n", file.getFileName(), e.getMessage());
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

										createMemoData.put("content", contentJoiner.toString());
										createMemoData.put("visibility", "PRIVATE");

										String createMemoJson = convertToJson(createMemoData);

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

													// System.out.println("Created memo: " + createMemoResponseData.get("name"));

													Map<String, Object> updateMemoData = new HashMap<>();

													updateMemoData.put("createTime", createdInstant);
													updateMemoData.put("updateTime", updatedInstant);
													updateMemoData.put("pinned", note.isPinned());

													if (note.isArchived())
														updateMemoData.put("state", "ARCHIVED");

													String updateMemoJson = convertToJson(updateMemoData);

													if (updateMemoJson != null) {
														HttpResponse<String> updateMemoResponse = httpClient.send(HttpRequest.newBuilder()
																		.uri(URI.create(memosUrl).resolve("/api/v1/" + createMemoResponseData.get("name")))
																		.timeout(Duration.ofSeconds(30L))
																		.header("Authorization", "Bearer " + memosAccessToken)
																		.method("PATCH", HttpRequest.BodyPublishers.ofString(updateMemoJson, StandardCharsets.UTF_8))
																		.build(),
																HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

														if (updateMemoResponse.statusCode() == 200) {
															successCounter.incrementAndGet();
														}
														else {
															failCounter.incrementAndGet();

															System.err.printf(
																	"Failed to update memo. Response: %s - %s%n",
																	updateMemoResponse.statusCode(),
																	updateMemoResponse.body().lines().collect(Collectors.joining(" | ")));
														}
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
																		System.err.printf("Failed to read file %s. %s%n", attachmentFile.getFileName(), e.getMessage());

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

																		if (resourceResponse.statusCode() != 200) {
																			System.err.printf("Failed to create resource. Response: %s - %s%n",
																					resourceResponse.statusCode(),
																					resourceResponse.body().lines().collect(Collectors.joining(" | ")));
																		}
																	}
																	catch (IOException | InterruptedException e) {
																		System.err.println("Failed to create resource. " + e.getMessage());
																	}
																}
															});
														}
													}
												}
												else {
													failCounter.incrementAndGet();

													System.err.printf("Failed to create memo. Response: %s - %s%n",
															createMemoResponse.statusCode(),
															createMemoResponse.body().lines().collect(Collectors.joining(" | ")));
												}
											}
											catch (IOException | InterruptedException e) {
												System.err.println("Failed to create memo. " + e.getMessage());
											}
										}
									}
									else {
										skipCounter.incrementAndGet();

										System.out.println("Skipping trashed note.");
									}
								}
								else {
									failCounter.incrementAndGet();

									System.err.println("Failed to read file: " + file.getFileName());
								}
							}
							else {
								failCounter.incrementAndGet();

								System.err.printf("File %s is empty.%n", file.getFileName());
							}
						});
			}
		}

		System.out.println();
		System.out.println("Files: " + fileCounter.get());
		System.out.println("Successful: " + successCounter.get());
		System.out.println("Failed: " + failCounter.get());
		System.out.println("Skipped: " + skipCounter.get());
	}

	private Map<String, String> parseParams(String[] args) {
		Map<String, String> params = new HashMap<>();

		String option = null;

		for (String arg : args) {
			if (arg.startsWith("--"))
				option = arg.substring(2);
			else if (option != null)
				params.put(option, arg);
		}

		return params;
	}

	private String convertToJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			System.err.println("Failed to serialize value as json. " + e.getMessage());
		}

		return null;
	}

	private Map<String, Object> convertToMap(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<>() {});
		}
		catch (JsonProcessingException e) {
			System.err.println("Failed to deserialize json. " + e.getMessage());
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

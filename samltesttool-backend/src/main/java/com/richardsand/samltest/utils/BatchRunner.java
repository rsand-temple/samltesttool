package com.richardsand.samltest.utils;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BatchRunner {

    private static final int BATCH_SIZE = 50;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage:");
            System.err.println("  java AicCsvBatchRunner <input.csv> <output.csv> <batchEndpointUrl>");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java AicCsvBatchRunner failures.csv enriched.csv http://localhost:8080/api/aic/logs/batch");
            System.exit(1);
        }

        Path   inputCsv    = Paths.get(args[0]);
        Path   outputCsv   = Paths.get(args[1]);
        String endpointUrl = args[2];

        ObjectMapper mapper = new ObjectMapper();
        HttpClient   http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        List<CSVRecord> records;
        @SuppressWarnings("deprecation")
        CSVFormat       format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        try (Reader reader = Files.newBufferedReader(inputCsv, StandardCharsets.UTF_8)) {
            records = format.parse(reader).getRecords();
        }

        List<String> txids = records.stream()
                .map(r -> r.get("txid"))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        Map<String, JsonNode> resultsByTxid = new LinkedHashMap<>();

        for (int i = 0; i < txids.size(); i += BATCH_SIZE) {
            List<String> batch = txids.subList(i, Math.min(i + BATCH_SIZE, txids.size()));

            ObjectNode requestJson = mapper.createObjectNode();
            requestJson.set("transactionIds", mapper.valueToTree(batch));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestJson)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Batch call failed HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            JsonNode responseArray = mapper.readTree(response.body());

            for (JsonNode result : responseArray) {
                String txid = result.path("transactionId").asText();
                resultsByTxid.put(txid, result);
            }

            System.out.printf("Processed %d / %d%n", Math.min(i + BATCH_SIZE, txids.size()), txids.size());
        }

        try (
                Writer writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8);
                @SuppressWarnings("deprecation")
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                        .setHeader("time", "txid", "localSpid", "remoteIssuer", "aicLogResponse")
                        .build())) {
            for (CSVRecord r : records) {
                String   txid      = r.get("txid").trim();
                JsonNode logResult = resultsByTxid.get(txid);

                printer.printRecord(
                        r.get("time"),
                        txid,
                        r.get("localSpid"),
                        r.get("remoteIssuer"),
                        logResult == null ? "" : mapper.writeValueAsString(logResult));
            }
        }

        System.out.println("Wrote: " + outputCsv.toAbsolutePath());
    }
}

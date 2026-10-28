package dev.rishav.copilot;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL integration, synthetic embeddings: no provider key or paid calls. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgVectorStoreIT {
    private HikariDataSource database;
    private JdbcTemplate jdbc;
    private PgVectorStore store;
    private final String schema = "copilot_it_" + UUID.randomUUID().toString().replace("-", "");
    private final EmbeddingModel embeddings = new FixtureEmbeddings();

    @BeforeAll void connect() {
        database = new AiConfiguration().dataSource(
                System.getenv().getOrDefault("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/copilot"),
                System.getenv().getOrDefault("TEST_DATABASE_USER", "copilot"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "copilot-local"));
        jdbc = new JdbcTemplate(database);
        store = createStore();
    }

    private PgVectorStore createStore() {
        var vectorStore = (PgVectorStore) new AiConfiguration().vectorStore(database, embeddings, schema);
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }

    @BeforeEach void clearFixtureRows() { jdbc.execute("TRUNCATE TABLE " + schema + ".vector_store"); }

    @AfterAll void cleanup() {
        if (database != null) {
            try {
                if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } finally { database.close(); }
        }
    }

    @Test void initializes1536DimensionalStorageAndCosineHnswIndex() {
        String type = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a JOIN pg_class c ON a.attrelid=c.oid
                JOIN pg_namespace n ON c.relnamespace=n.oid
                WHERE n.nspname=? AND c.relname='vector_store' AND a.attname='embedding'
                """, String.class, schema);
        assertThat(type).isEqualTo("vector(1536)");
        var indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname=?", String.class, schema);
        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("USING hnsw", "vector_cosine_ops"));
    }

    @Test void ordersByCosineSimilarityAndPersistsAcrossStoreInstances() {
        String relevant = UUID.randomUUID().toString();
        store.add(List.of(new Document(relevant, "PRICE_BOUNDARY", Map.of()),
                new Document(UUID.randomUUID().toString(), "UNRELATED", Map.of())));
        var reopened = createStore();
        var results = reopened.similaritySearch(SearchRequest.builder().query("PRICE_BOUNDARY").topK(2).build());
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().getId()).isEqualTo(relevant);
        assertThat(results.getFirst().getScore()).isCloseTo(1.0, within(0.0001));
        assertThat(results.get(1).getScore()).isLessThan(results.getFirst().getScore());
        reopened.delete(List.of(relevant));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".vector_store", Integer.class)).isEqualTo(1);
    }

    @Test void retrievalEnforcesSpecificationAndEndpointFiltersInDatabase() throws Exception {
        var endpoints = new OpenApiReader().read(OpenApiReaderTest.example());
        var selected = endpoints.get(1);
        var spec = new Specification(UUID.randomUUID(), "Selected", List.of(selected), Instant.now());
        var knowledge = new AiKnowledge(store);
        knowledge.index(spec, "SELECTED_DOCUMENTATION");
        // Give unrelated documents identical vectors: similarity alone cannot exclude them.
        store.add(List.of(
                new Document(UUID.randomUUID().toString(), "WRONG_SPEC", Map.of("specId", UUID.randomUUID().toString(), "endpointKey", selected.key())),
                new Document(UUID.randomUUID().toString(), "WRONG_ENDPOINT", Map.of("specId", spec.id().toString(), "endpointKey", "GET /products"))));
        var evidence = knowledge.retrieve(spec.id(), selected);
        assertThat(evidence).hasSize(3); // Deterministic anchor plus two actual stored chunks.
        assertThat(evidence).anySatisfy(e -> assertThat(e.content()).contains("SELECTED_DOCUMENTATION"));
        assertThat(evidence).allSatisfy(e -> assertThat(e.content()).doesNotContain("WRONG_SPEC", "WRONG_ENDPOINT"));
        assertThat(evidence.stream().filter(e -> !e.id().startsWith("contract:")).toList()).hasSize(2);
    }

    private static class FixtureEmbeddings implements EmbeddingModel {
        @Override public int dimensions() { return 1536; }
        @Override public float[] embed(Document document) { return vector(document.getText()); }
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(vector(request.getInstructions().get(i)), i)).toList());
        }
        private float[] vector(String text) {
            float[] vector = new float[1536];
            vector[0] = text.contains("UNRELATED") ? 0.2f : 1f;
            vector[1] = text.contains("UNRELATED") ? 1f : 0f;
            return vector;
        }
    }
}

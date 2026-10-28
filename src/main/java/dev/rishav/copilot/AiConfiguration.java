package dev.rishav.copilot;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@Profile("ai")
public class AiConfiguration {
    @Bean
    OpenAiApi provider(@Value("${OPENAI_API_KEY}") String key) {
        if (key.isBlank())
            throw new IllegalStateException("AI mode requires OPENAI_API_KEY in the environment");
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(45));
        return OpenAiApi.builder().apiKey(key).restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }

    @Bean
    ChatClient chatClient(OpenAiApi api, @Value("${copilot.model}") String model) {
        return ChatClient.create(OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).maxTokens(3500).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(2).fixedBackoff(500).build()).build());
    }

    @Bean
    EmbeddingModel embeddings(OpenAiApi api) {
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, OpenAiEmbeddingOptions.builder()
                .model("text-embedding-3-small").dimensions(1536).build(),
                RetryTemplate.builder().maxAttempts(2).fixedBackoff(500).build());
    }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${copilot.database-url}") String url,
            @Value("${copilot.database-user}") String user, @Value("${copilot.database-password}") String password) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(4);
        ds.setConnectionTimeout(5000);
        return ds;
    }

    @Bean
    VectorStore vectorStore(HikariDataSource ds, EmbeddingModel embedding,
            @Value("${copilot.vector-schema:public}") String schema) {
        return PgVectorStore.builder(new JdbcTemplate(ds), embedding).schemaName(schema).dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW).initializeSchema(true).build();
    }
}

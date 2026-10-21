package com.triarii.rf.ingest.rule;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.triarii.rf.ingest.config.IngestProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Seeds rules from the rules file at startup with insert-if-absent semantics, so changes made through the API
 * survive restarts. An invalid file fails startup rather than running with a partial rule set.
 */
@Component
public class RuleSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleSeeder.class);

    record RulesFile(List<Rule> rules) {
    }

    private final RuleRepository repository;
    private final RuleValidator validator;
    private final RuleCache cache;
    private final ResourceLoader resourceLoader;
    private final IngestProperties properties;

    public RuleSeeder(RuleRepository repository, RuleValidator validator, RuleCache cache,
            ResourceLoader resourceLoader, IngestProperties properties) {
        this.repository = repository;
        this.validator = validator;
        this.cache = cache;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @PostConstruct
    void seed() throws IOException {
        Resource resource = resourceLoader.getResource(properties.rulesFile());
        if (!resource.exists()) {
            throw new IllegalStateException("Rules file not found: " + properties.rulesFile());
        }

        YAMLMapper yaml = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        RulesFile file;
        try (InputStream in = resource.getInputStream()) {
            file = yaml.readValue(in, RulesFile.class);
        }

        int inserted = 0;
        List<Rule> rules = file.rules() == null ? List.of() : file.rules();
        for (Rule rule : rules) {
            Rule normalized = rule.withDefaults();
            List<String> errors = validator.validate(normalized);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Invalid rule '" + rule.ruleId() + "' in " + properties.rulesFile()
                        + ": " + errors);
            }
            if (repository.insert(normalized)) {
                inserted++;
            }
        }
        log.info("Rules file {}: {} rules, {} newly inserted", properties.rulesFile(), rules.size(), inserted);
        cache.reload();
    }
}

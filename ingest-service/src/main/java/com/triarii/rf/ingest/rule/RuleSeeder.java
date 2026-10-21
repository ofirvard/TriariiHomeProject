package com.triarii.rf.ingest.rule;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Seeds rules from the rules file at startup with insert-if-absent semantics, so changes made through the API
 * survive restarts. An invalid file fails startup rather than running with a partial rule set.
 */
@Component
public class RuleSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleSeeder.class);

    record RulesFile(List<RuleModel> rules) {
    }

    private final RuleRepository repository;
    private final RuleValidator validator;
    private final Resource rulesFile;

    public RuleSeeder(RuleRepository repository, RuleValidator validator,
            @Value("${rf.rules-file}") Resource rulesFile) {
        this.repository = repository;
        this.validator = validator;
        this.rulesFile = rulesFile;
    }

    @PostConstruct
    void seed() throws IOException {
        YAMLMapper yaml = YAMLMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        List<RuleModel> rules;
        try (InputStream in = rulesFile.getInputStream()) {
            rules = yaml.readValue(in, RulesFile.class).rules();
        }
        int inserted = 0;
        for (RuleModel rule : rules) {
            RuleModel normalized = rule.normalized(rule.ruleId());
            List<String> errors = validator.validate(normalized);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Invalid rule '" + rule.ruleId() + "' in rules file: " + errors);
            }
            if (repository.insert(normalized)) {
                inserted++;
            }
        }
        log.info("Rules file {}: {} rules, {} newly inserted", rulesFile, rules.size(), inserted);
    }
}

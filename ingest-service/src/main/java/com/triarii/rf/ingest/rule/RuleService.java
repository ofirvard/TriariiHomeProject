package com.triarii.rf.ingest.rule;

import com.triarii.rf.ingest.web.ValidationFailedException;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleService {

    private final RuleRepository repository;
    private final RuleValidator validator;
    private final ApplicationEventPublisher events;

    public RuleService(RuleRepository repository, RuleValidator validator, ApplicationEventPublisher events) {
        this.repository = repository;
        this.validator = validator;
        this.events = events;
    }

    public List<RuleModel> list() {
        return repository.findAll();
    }

    public RuleModel get(String ruleId) {
        return repository.findById(ruleId).orElseThrow(() -> notFound(ruleId));
    }

    public RuleModel create(RuleModel body) {
        RuleModel rule = validated(body.withDefaults());
        if (!repository.insert(rule)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rule '" + rule.ruleId() + "' already exists");
        }
        events.publishEvent(new RuleChangedEvent(rule.ruleId()));
        return rule;
    }

    /** Full replace; also how a rule is enabled or disabled. The id in the path wins. */
    public RuleModel update(String ruleId, RuleModel body) {
        if (body.ruleId() != null && !body.ruleId().equals(ruleId)) {
            throw new ValidationFailedException(List.of("rule_id: does not match the id in the path"));
        }
        RuleModel rule = validated(body.withRuleId(ruleId).withDefaults());
        if (!repository.update(rule)) {
            throw notFound(ruleId);
        }
        events.publishEvent(new RuleChangedEvent(ruleId));
        return rule;
    }

    public void delete(String ruleId) {
        if (!repository.delete(ruleId)) {
            throw notFound(ruleId);
        }
        events.publishEvent(new RuleChangedEvent(ruleId));
    }

    private RuleModel validated(RuleModel rule) {
        List<String> errors = validator.validate(rule);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return rule;
    }

    private static ResponseStatusException notFound(String ruleId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "rule '" + ruleId + "' not found");
    }
}

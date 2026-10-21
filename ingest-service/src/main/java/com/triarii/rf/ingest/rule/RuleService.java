package com.triarii.rf.ingest.rule;

import com.triarii.rf.ingest.engine.RuleEngine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleService {

    private final RuleRepository repository;
    private final RuleValidator validator;
    private final RuleEngine engine;

    public RuleService(RuleRepository repository, RuleValidator validator, RuleEngine engine) {
        this.repository = repository;
        this.validator = validator;
        this.engine = engine;
    }

    public List<RuleModel> list() {
        return repository.findAll();
    }

    public RuleModel create(RuleModel body) {
        RuleModel rule = validated(body.normalized(body.ruleId()));
        if (!repository.insert(rule)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rule '" + rule.ruleId() + "' already exists");
        }
        return rule;
    }

    /** Full replace; also how a rule is enabled or disabled. */
    public RuleModel update(String ruleId, RuleModel body) {
        RuleModel rule = validated(body.normalized(ruleId));
        if (!repository.update(rule)) {
            throw notFound(ruleId);
        }
        engine.forget(ruleId); // windows built under the old settings no longer apply
        return rule;
    }

    public void delete(String ruleId) {
        if (!repository.delete(ruleId)) {
            throw notFound(ruleId);
        }
        engine.forget(ruleId);
    }

    private RuleModel validated(RuleModel rule) {
        List<String> errors = validator.validate(rule);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
        return rule;
    }

    private static ResponseStatusException notFound(String ruleId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "rule '" + ruleId + "' not found");
    }
}

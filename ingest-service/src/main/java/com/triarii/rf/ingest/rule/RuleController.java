package com.triarii.rf.ingest.rule;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final RuleService service;

    public RuleController(RuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<Rule> list() {
        return service.list();
    }

    @GetMapping("/{ruleId}")
    public Rule get(@PathVariable String ruleId) {
        return service.get(ruleId);
    }

    @PostMapping
    public ResponseEntity<Rule> create(@RequestBody Rule rule) {
        Rule created = service.create(rule);
        return ResponseEntity.created(URI.create("/api/v1/rules/" + created.ruleId())).body(created);
    }

    @PutMapping("/{ruleId}")
    public Rule update(@PathVariable String ruleId, @RequestBody Rule rule) {
        return service.update(ruleId, rule);
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable String ruleId) {
        service.delete(ruleId);
        return ResponseEntity.noContent().build();
    }
}

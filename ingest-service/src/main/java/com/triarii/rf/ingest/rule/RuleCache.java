package com.triarii.rf.ingest.rule;

import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Enabled rules kept in memory so evaluating an observation never hits the database. */
@Component
public class RuleCache {

    private final RuleRepository rules;
    private volatile List<RuleModel> enabled = List.of();

    public RuleCache(RuleRepository rules) {
        this.rules = rules;
    }

    public List<RuleModel> enabled() {
        return enabled;
    }

    public void reload() {
        enabled = rules.findAll().stream().filter(RuleModel::enabled).toList();
    }

    @EventListener
    void onRuleChanged(RuleChangedEvent event) {
        reload();
    }
}

package org.debian.decopy.tree;

import org.debian.decopy.datatypes.CopyrightHolder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A collection of copyright holders, merged by person/email.
 */
public final class CopyrightGroup {

    private final Map<String, CopyrightHolder> members = new LinkedHashMap<>();
    private final Map<String, CopyrightHolder> byEmail = new HashMap<>();

    public int size() { return members.size(); }
    public boolean isEmpty() { return members.isEmpty(); }

    public Iterable<CopyrightHolder> values() { return members.values(); }

    public CopyrightHolder add(CopyrightHolder holder) {
        if (!holder.getEmail().isEmpty() && byEmail.containsKey(holder.getEmail())) {
            CopyrightHolder prev = byEmail.get(holder.getEmail());
            return prev.merge(holder);
        }
        String person = holder.getPerson();
        if (members.containsKey(person)) {
            return members.get(person).merge(holder);
        }
        if (!holder.getEmail().isEmpty()) {
            byEmail.put(holder.getEmail(), holder);
        }
        members.put(person, holder);
        return holder;
    }

    public void extend(List<CopyrightHolder> holders) {
        for (CopyrightHolder h : holders) {
            add(h);
        }
    }

    public Object key() {
        List<String> keys = members.values().stream()
                .map(h -> h.getEmail().isEmpty() ? h.getPerson() : h.getEmail())
                .sorted()
                .collect(Collectors.toList());
        return keys;
    }

    public CopyrightGroup merge(CopyrightGroup other) {
        for (CopyrightHolder h : other.members.values()) {
            add(h);
        }
        return this;
    }

    public List<String> sortedMembers() {
        return members.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().toString())
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "CopyrightGroup(" + sortedMembers() + ")";
    }
}

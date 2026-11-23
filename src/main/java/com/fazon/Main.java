package com.fazon;

import com.fazon.barrier.KekkaiSandbox;

import java.time.Duration;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        var barrier = KekkaiSandbox.readOnly();

        Map<String, Object> orders = Map.of(
                "ORD-101", 250.00,
                "ORD-102", 1400.50,
                "ORD-103", 89.99
        );

        // TODO(@zeke) - add a test for the case where the agent is blocked by the sandbox
        String safeCode = """
        @SuppressWarnings("unchecked")
        Map<String, Double> data = (Map<String, Double>) ctx.get("orders");
        return data.entrySet().stream()
            .filter(e -> e.getValue() > 200.0)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        """;
        IO.println("Safe result: " + barrier.run(safeCode, Map.of("orders", orders), Duration.ofSeconds(2)));

        String evilCode = """
        System.exit(1);
        return null;
        """;
        try {
            barrier.run(evilCode, Map.of("orders", orders), Duration.ofSeconds(2));
        } catch (SecurityException e) {
            System.out.println("Blocked malicious code: " + e.getMessage());
        }

        String mutateCode = """
        ctx.put("hacked", true);
        return null;
        """;
        try {
            barrier.run(mutateCode, Map.of("orders", orders), Duration.ofSeconds(2));
        } catch (RuntimeException e) {
            IO.println("Blocked mutation: " + e.getCause());
        }
    }
}









package com.fazon;

import com.fazon.barrier.KekkaiSandbox;
import com.fazon.barrier.agent.ReadOnlyAgentSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyAgentSandboxTest {

    @Nested
    class CompileInMemory {
        @Test
        @DisplayName("LLM Generated Pattern - Anon Classes")
        public void anonClassGeneration() throws Exception {
            var agent = KekkaiSandbox.readOnly();
            Map<String, Object> orders = Map.of(
                    "ORD-101", 250.00,
                    "ORD-102", 1400.50,
                    "ORD-103", 89.99
            );
            var anonClassCode = """
                    List<String> keys = new ArrayList<>(((Map<String, Double>) ctx.get("orders")).keySet());
                    keys.sort(new Comparator<String>() {
                        @Override
                        public int compare(String a, String b) { return b.compareTo(a); }
                    });
                    return keys;
                    """;

            var result = agent.run(anonClassCode, Map.of("orders", orders), Duration.ofSeconds(2));
            assertEquals(List.of("ORD-103", "ORD-102", "ORD-101"), result);
        }

        @Test
        @DisplayName("LLM Generated Pattern - Lambdas without returns")
        public void generatedLambdas()  {
            var agent = KekkaiSandbox.readOnly();

            var inputSupplier = """
            Supplier<List<String>> inputSupplier = () -> List.of("agent", "sandbox", "java", "lambda", "test");
            """;

            assertThrows(IllegalArgumentException.class, () -> agent.run(inputSupplier, Map.of(), Duration.ofSeconds(2)));
        }

        @Test
        @DisplayName("LLM Generated Pattern - Nested Collection Mutation")
        public void nestedCollectionMutation() {
            var agent = KekkaiSandbox.readOnly();

            List<String> items = new ArrayList<>(List.of("a", "b"));
            Map<String, Object> mutableNested = Map.of("items", items);

            String nestedMutate = """
                    ((List<String>) ctx.get("items")).add("hacked");
                    return ctx.get("items");
                    """;

            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> agent.run(nestedMutate, mutableNested, Duration.ofSeconds(2))
            );

            assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
            assertEquals(List.of("a", "b"), items);
        }

        @Test
        @DisplayName("LLM Generated Pattern - Reflection Escape")
        public void reflectionEscape() {
            var agent = KekkaiSandbox.readOnly();
            String reflectionEscape = """
                    try {
                        Class<?> sys = Class.forName("java.lang.System");
                        return sys.getName();
                    } catch (Exception e) {
                        return e.toString();
                    }
                    """;
            SecurityException exception = assertThrows(
                    SecurityException.class,
                    () -> agent.run(reflectionEscape, Map.of(), Duration.ofSeconds(2))
            );

            assertTrue(exception.getMessage().contains("java/lang/Class"));
        }
    }

}
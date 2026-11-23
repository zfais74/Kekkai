package com.fazon.barrier;

import com.fazon.barrier.agent.Agent;
import com.fazon.barrier.agent.ReadOnlyAgentSandbox;
import com.fazon.barrier.context.ContextEntry;

import java.time.Duration;
import java.util.*;

public class KekkaiSandbox {
    private final SandboxMode mode;
    private final Agent agent;

    private KekkaiSandbox(Builder builder) {
        this.mode = builder.mode;
        this.agent = builder.agent != null
                ? builder.agent
                : createAgent(builder.mode, builder.allowedPackages);
    }

    public static KekkaiSandbox readOnly() {
        return readOnlyBuilder().build();
    }

    public static Builder readOnlyBuilder() {
        return new Builder().mode(SandboxMode.READ_ONLY);
    }

    private static Agent createAgent(SandboxMode mode, Set<String> allowedPackages) {
        return switch (mode) {
            case READ_ONLY -> new ReadOnlyAgentSandbox(allowedPackages);
        };
    }

    public Object run(String agentJavaBody, List<ContextEntry<?>> context, Duration timeout) throws Exception {
        return switch (agent) {
            case ReadOnlyAgentSandbox readOnlyAgentSandbox -> readOnlyAgentSandbox.run(agentJavaBody, context, timeout);
            default -> throw new UnsupportedOperationException("Unsupported agent: " + agent);
        };
    }

    static Set<String> provideDefaultPrefixes() {
        return Set.of("java/lang/Object",
                "java/lang/String",
                "java/lang/Number",
                "java/lang/Integer",
                "java/lang/Long",
                "java/lang/Double",
                "java/lang/Boolean",
                "java/lang/Math",
                "java/lang/Iterable",
                "java/lang/Comparable",
                "java/lang/invoke/", // needed for lambdas & string concatenation
                "java/util/",
                "java/time/",
                "java/math/",
                "com/fazon/barrier/agent/AgentTask");
    }


    public static class Builder {
        private SandboxMode mode = SandboxMode.READ_ONLY;
        private final Set<String> allowedPackages = new java.util.LinkedHashSet<>(provideDefaultPrefixes());
        private Agent agent;

        public Builder mode(SandboxMode mode) {
            this.mode = Objects.requireNonNull(mode, "Sandbox mode is required");
            return this;
        }

        public Builder allowPackage(String internalPackagePrefix) {
            allowedPackages.add(validatePackagePrefix(internalPackagePrefix));
            return this;
        }

        public Builder allowClass(String internalClassName) {
            allowedPackages.add(validateClassName(internalClassName));
            return this;
        }

        public Builder agent(Agent agent) {
            this.agent = Objects.requireNonNull(agent, "Agent is required");
            return this;
        }

        public KekkaiSandbox build() {
            Objects.requireNonNull(mode, "Sandbox mode is required");
            return new KekkaiSandbox(this);
        }

        private static String validatePackagePrefix(String internalPackagePrefix) {
            Objects.requireNonNull(internalPackagePrefix, "Package prefix is required");

            if (!internalPackagePrefix.endsWith("/")) {
                throw new IllegalArgumentException("Package prefixes must use JVM internal names and end with '/': " + internalPackagePrefix);
            }

            if (internalPackagePrefix.equals("java/")
                    || internalPackagePrefix.equals("java/lang/")
                    || internalPackagePrefix.equals("javax/")
                    || internalPackagePrefix.equals("jdk/")
                    || internalPackagePrefix.equals("sun/")
                    || internalPackagePrefix.equals("com/sun/")) {
                throw new IllegalArgumentException("Package prefix is too broad for the sandbox allowlist: " + internalPackagePrefix);
            }

            return internalPackagePrefix;
        }

        private static String validateClassName(String internalClassName) {
            Objects.requireNonNull(internalClassName, "Class name is required");

            if (internalClassName.endsWith("/")) {
                throw new IllegalArgumentException("Class names must not end with '/': " + internalClassName);
            }

            return internalClassName;
        }
    }
}

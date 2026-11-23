package com.fazon.barrier.agent;

import com.fazon.barrier.context.ContextEntry;

import javax.tools.*;
import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * A read-only sandbox. This class generates an Agent snippet dynamically. The agent will evaluate the incoming code
 *
 * @param ALLOWED_PACKAGE_PREFIXES Java packages that are allowed to be referenced by the agent. This can be a single package name or a list of package names.
 */
public record ReadOnlyAgentSandbox(Set<String> ALLOWED_PACKAGE_PREFIXES) implements Agent {
    public ReadOnlyAgentSandbox {
        Objects.requireNonNull(ALLOWED_PACKAGE_PREFIXES, "Allowed package prefixes must be provided to the agent");
        if (ALLOWED_PACKAGE_PREFIXES.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed package prefix must be provided");
        }
    }

    private static Map<String, byte[]> compileInMemory(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK required (JRE lacks JavaCompiler)");

        Map<String, ByteArrayOutputStream> compiledBytes = new HashMap<>();
        StandardJavaFileManager stdManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);

        JavaFileManager memManager = new ForwardingJavaFileManager<>(stdManager) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location loc, String name, JavaFileObject.Kind kind, FileObject sibling) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                compiledBytes.put(name, baos);
                return new SimpleJavaFileObject(URI.create("mem:///" + name + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return baos;
                    }
                };
            }
        };

        JavaFileObject srcFile = new SimpleJavaFileObject(URI.create("mem:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok = compiler.getTask(null, memManager, diagnostics, List.of("-proc:none"), null, List.of(srcFile)).call();
        if (!ok) {
            String message = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
                    .collect(Collectors.joining(System.lineSeparator()));

            throw new IllegalArgumentException("""
                    Compilation failed.
                    
                    The agent body is inserted into:
                    
                    public Object execute(Map<String, Object> ctx) {
                        // your code here
                    }
                    
                    Because execute(...) returns Object, the agent body must return a value on every path.
                    
                    Compiler diagnostics:
                    %s
                    """.formatted(message));
        }

        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, ByteArrayOutputStream> entry : compiledBytes.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return result;
    }

    /**
     * Run the agent in a read-only sandbox.
     *
     * @param agentJavaBody Java code that will be executed in the sandbox.
     * @param context       The context that will be passed to the agent. The context is immutable and cannot be modified by the agent.
     * @param timeout       The maximum time the agent can run. We cannot guarantee that the agent will finish within this time.
     * @return The result of the agent.
     * @throws Exception
     */
    public Object run(String agentJavaBody, List<ContextEntry<?>> context, Duration timeout) throws Exception {
        String className = "AgentSnippet_" + UUID.randomUUID().toString().replace("-", "");
        String source = """
                import java.util.*;
                import java.util.function.*;
                import java.util.stream.*;
                import java.time.*;
                import java.math.*;
                import com.fazon.barrier.agent.AgentTask;
                
                public class %s implements AgentTask {
                    @Override
                    public Object execute(Map<String, Object> ctx) {
                        %s
                    }
                }
                """.formatted(className, agentJavaBody);

        Map<String, byte[]> bytecodeByClassName = compileInMemory(className, source);

        for (byte[] bytecode : bytecodeByClassName.values()) {
            verifyBytecode(bytecode, className);
        }

        Class<?> clazz = getAClass(bytecodeByClassName, className);
        AgentTask task = (AgentTask) clazz.getDeclaredConstructor().newInstance();

        Map<String, Object> frozenContext = freezeContext(toContextMap(context));

        // need to set a timeout on the VT
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> future = executor.submit(() -> task.execute(frozenContext));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new SecurityException("Agent execution exceeded timeout of " + timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String causeMessage = cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getName()
                    : cause.getClass().getName() + ": " + cause.getMessage();

            throw new RuntimeException("Agent execution failed with " + causeMessage, cause);
        }
    }

    private static Map<String, Object> toContextMap(List<ContextEntry<?>> context) {
        Objects.requireNonNull(context, "Context cannot be null");

        Map<String, Object> result = new LinkedHashMap<>();
        for (ContextEntry<?> entry : context) {
            Objects.requireNonNull(entry, "Context entry cannot be null");

            if (result.containsKey(entry.name())) {
                throw new IllegalArgumentException("Duplicate context entry name: " + entry.name());
            }

            result.put(entry.name(), entry.value());
        }

        return result;
    }

    private static Class<?> getAClass(Map<String, byte[]> bytecodeByClassName, String className) throws ClassNotFoundException {
        ClassLoader sandboxLoader = new ClassLoader(ReadOnlyAgentSandbox.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytecode = bytecodeByClassName.get(name);
                if (bytecode != null) {
                    return defineClass(name, bytecode, 0, bytecode.length);
                }
                throw new ClassNotFoundException(name);
            }
        };

        Class<?> clazz = sandboxLoader.loadClass(className);
        return clazz;
    }

    private static Map<String, Object> freezeContext(Map<String, Object> context) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            frozen.put(entry.getKey(), freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freezeValue(Object value) {

        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case Number n -> {
                return n;
            }
            case Boolean b -> {
                return b;
            }
            case Character c -> {
                return c;
            }
            case Enum<?> e -> {
                return e;
            }
            case Duration d -> {
                return d;
            }
            case Map<?, ?> m -> {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    frozen.put(freezeValue(entry.getKey()), freezeValue(entry.getValue()));
                }
                return Collections.unmodifiableMap(frozen);
            }
            case List<?> l -> {
                List<Object> frozen = new ArrayList<>(l.size());
                for (Object item : l) {
                    frozen.add(freezeValue(item));
                }
                return Collections.unmodifiableList(frozen);
            }
            case Set<?> s -> {
                Set<Object> frozen = new LinkedHashSet<>();
                for (Object item : s) {
                    frozen.add(freezeValue(item));
                }
                return Collections.unmodifiableSet(frozen);
            }
            case Collection<?> c -> {
                List<Object> frozen = new ArrayList<>(c.size());
                for (Object item : c) {
                    frozen.add(freezeValue(item));
                }
                return Collections.unmodifiableList(frozen);
            }
            default ->
                    throw new IllegalArgumentException("Unsupported mutable context value type: " + value.getClass().getName());
        }
    }

    /**
     * Verify byte code uses Java's new Class-File API to avoid inspection overhead
     * @param classBytes
     * @param generatedClassName
     */
    void verifyBytecode(byte[] classBytes, String generatedClassName) {
        ClassModel model = ClassFile.of().parse(classBytes);

        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry classEntry) {
                String owner = classEntry.asInternalName();

                if (!isGeneratedSnippetClass(owner, generatedClassName) && !isAllowedOwner(owner)) {
                    throw new SecurityException("Blocked class reference: " + owner);
                }
            }

            if (entry instanceof MemberRefEntry ref) {
                String owner = ref.owner().asInternalName();
                String name = ref.name().stringValue();

                if (!isGeneratedSnippetClass(owner, generatedClassName) && !isAllowed(owner, name)) {
                    throw new SecurityException("Blocked call: " + owner + "." + name);
                }
            }
        }
    }

    private boolean isGeneratedSnippetClass(String owner, String generatedClassName) {
        return owner.equals(generatedClassName) || owner.startsWith(generatedClassName + "$");
    }

    public boolean isAllowed(String owner, String name) {
        return isAllowedOwner(owner) && isAllowedMember(owner, name);
    }

    private boolean isAllowedOwner(String owner) {
        return ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(allowedName ->
                allowedName.endsWith("/")
                        ? owner.startsWith(allowedName)
                        : owner.equals(allowedName)
        );
    }

    private boolean isAllowedMember(String owner, String name) {
        if (owner.equals("java/lang/System")) {
            return switch (name) {
                case "lineSeparator", "nanoTime", "currentTimeMillis" -> true;
                default -> false;
            };
        }

        return !owner.equals("java/lang/Class")
                && !owner.equals("java/lang/ClassLoader")
                && !owner.equals("java/lang/Runtime")
                && !owner.equals("java/lang/ProcessBuilder")
                && !owner.startsWith("java/lang/reflect/")
                && !owner.startsWith("java/lang/invoke/MethodHandle")
                && !owner.startsWith("java/lang/invoke/MethodHandles");
    }
}

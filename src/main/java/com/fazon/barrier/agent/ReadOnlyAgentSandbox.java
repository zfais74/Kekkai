package com.fazon.barrier.agent;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
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
    public Object run(String agentJavaBody, Map<String, Object> context, Duration timeout) throws Exception {
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
            verifyConstantPool(bytecode, className);
        }

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
        AgentTask task = (AgentTask) clazz.getDeclaredConstructor().newInstance();

        Map<String, Object> frozenContext = freezeContext(context);

        // need to set a timeout on the VT
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> future = executor.submit(() -> task.execute(frozenContext));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new SecurityException("Agent execution exceeded timeout of " + timeout);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() instanceof InvocationTargetException ite) ? ite.getCause() : e.getCause();
            throw new RuntimeException("Agent execution failed: " + cause.getMessage(), cause);
        }
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


    public void verifyConstantPool(byte[] classBytes, String selfClassName) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) throw new SecurityException("Invalid classfile");
            in.readUnsignedShort();
            in.readUnsignedShort();

            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            List<Integer> classNameIndices = new ArrayList<>();

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIndices.add(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 9, 10, 11, 12, 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new SecurityException("Unsupported constant pool tag: " + tag);
                }
            }

            for (int idx : classNameIndices) {
                String internalName = utf8[idx];

                if (internalName == null || internalName.startsWith("[")) {
                    continue;
                }

                boolean generatedAgentClass = internalName.equals(selfClassName)
                        || internalName.startsWith(selfClassName + "$");

                if (generatedAgentClass) {
                    continue;
                }

                boolean allowed = ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(allowedName ->
                        allowedName.endsWith("/")
                                ? internalName.startsWith(allowedName)
                                : internalName.equals(allowedName)
                );

                if (!allowed) {
                    throw new SecurityException("Blocked unauthorized class reference in agent bytecode: " + internalName);
                }
            }
        }
    }
}

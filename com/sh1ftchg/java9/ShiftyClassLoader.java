package com.sh1ftchg.java9;

import org.objectweb.asm.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Apply patches to Forge to bring it full functionality on Java 9.
 * Specifically, sun.reflect was migrated to a module, so we redirect to the module.
 * Also, the JVM no longer uses an URLClassLoader to boot, so we take its place as that's what Forge expects.
 *
 * @author sh1ftchg
 * @version 2.0.0
 * @apiNote This isn't an API. TRUTH.
 */
public class ShiftyClassLoader extends URLClassLoader {
    private static final PrintStream out;
    private static final URL[] bootClassPath;
    private static final boolean java9;
    private static final Map<String, Class<?>> loaded;
    public static final SunAccessVisitor visitor;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);

    static {
        loaded = new HashMap<>();
        out = System.out;
        java9 = System.getProperty("java.version").startsWith("9");
        if (java9) {
            out.println("[ShiftyClassLoader] Java 9 detected. Time to be more than a pointless wrapper.");
        }
        URL[] urls;
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && classPath.length() > 0) {
            HashSet<URL> set = new HashSet<URL>();
            appendClassPath(classPath, set);
            urls = set.toArray(new URL[set.size()]);
        } else {
            urls = new URL[]{};
        }
        bootClassPath = urls;
        visitor = new SunAccessVisitor(Opcodes.ASM5);
    }

    private static URL urlify(String s) {
        try {
            return Paths.get(s).toRealPath().toFile().toURI().toURL();
        } catch (InvalidPathException | IOException ignored) {
            return null;
        }
    }

    /* A clone what the JVM boot classloader's do to get a listing of the classpath... Only 'smarterized' (trademarked,
     * 2017, sh1ftchg.com but lulz not really), cause we use a set to prevent duplicates! */
    private static void appendClassPath(String cp, Set<URL> urls) {
        int cur = 0;
        int next;
        while ((next = cp.indexOf(File.pathSeparator, cur)) != -1) {
            URL url = urlify(cp.substring(cur, next));
            if (url != null)
                urls.add(url);
            cur = next + 1;
        }
        URL url = urlify(cp.substring(cur));
        if (url != null)
            urls.add(url);
    }

    /* Java 9 interop-ability necessity. For some reason, this functionality is expected/needed. I CBF'ed to figure out
     * what JEP made it so, therefore let's leave it at that. */
    void appendToClassPathForInstrumentation(String path) {
        int cur = 0;
        int next;
        while ((next = path.indexOf(File.pathSeparator, cur)) != -1) {
            URL url = urlify(path.substring(cur, next));
            if (url != null)
                addURL(url);
            cur = next + 1;
        }
        URL url = urlify(path.substring(cur));
        if (url != null)
            addURL(url);
    }

    /* This is what gets called when we get to be the System ClassLoader. */
    public ShiftyClassLoader(ClassLoader parent) {
        this(bootClassPath, parent);
    }

    /* The normal URLClassLoader initializer. */
    public ShiftyClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /* Theoretically, this only gets called when we're in java 9. Regardless, it'll just return the original name,
     * mostly. */
    public static String remap(String name) {
        if (name.startsWith("sun.reflect.")) {
            String remap = "jdk.internal.reflect." + name.substring("sun.reflect.".length());
            return remap;
        }
        return name;
    }

    public static final class SunAccessVisitor extends ClassVisitor {
        private final class MITMPatcher extends MethodVisitor {
            MITMPatcher(int api) {
                super(api);
            }

            final MethodVisitor setVisitor(MethodVisitor mv) {
                this.mv = mv;
                return this;
            }

            @Override
            public final void visitLdcInsn(Object cst) {
                if (cst instanceof String) {
                    cst = remap((String) cst);
                }
                super.visitLdcInsn(cst);
            }
        }

        private final MITMPatcher patcher = new MITMPatcher(Opcodes.ASM5);

        public SunAccessVisitor(int api) {
            super(api);
        }

        public final ClassVisitor delegate(ClassVisitor cv) {
            this.cv = cv;
            return this;
        }

        @Override
        public final MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            return patcher.setVisitor(super.visitMethod(access, name, desc, signature, exceptions));
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
        }
    }


    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Do we need some magic?

        if (!java9)
            // NOTHING TO SEE HERE, MOVE ALONG.
            return super.loadClass(name, resolve);
        // Yes. Yes we do. So let's make some magic happen!

        name = remap(name);
		
        if (!name.startsWith("net.minecraft") && name.indexOf('.') != -1)
            return super.loadClass(name, resolve);

        // Check cache first.
        Class<?> result = loaded.get(name);
        if (result != null)
            return result;

        // No cached result, delegate the resolution of the class to the parent classloaders.
        URL url = super.getResource(name.replace('.', '/') + ".class");
        if (url != null) {
            // Delegation successful, like a boss!
            try (InputStream in = url.openStream()) {
                final byte[] bytes;
				// Subjugate a primary offender.
                if (name.equals("net.minecraft.launchwrapper.LaunchClassLoader")) {
                    final ClassReader reader = new ClassReader(in);
                    final ClassWriter writer = new ClassWriter(reader, 0);
                    reader.accept(new ClassVisitor(ASM5, writer) {
                        @Override
                        public final MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                            if (name.equals("findClass")) {
                                mv = new MethodVisitor(ASM5, mv) {
                                    @Override
                                    public final void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                                        // Redirect this specific call to an injected method.
                                        if (name.equals("defineClass"))
                                            name = "java9_defineClass";
                                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                                    }
                                };
                            }
                            return mv;
                        }

                        @Override
                        public final void visitEnd() {
                            // Inject our method...

                            // Feel free to optimize this, I wrote the bytecode by hand and only cared if it worked.
                            // It basically does what we're doing here, but makes use of our SunAccessVisitor to do
                            // the heavy lifting... I'm not hardcore enough to hand jam EVERYTHING.

                            // Mimic the method they meant to call.
                            MethodVisitor mv = super.visitMethod(ACC_PROTECTED + ACC_FINAL, "java9_defineClass", "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;", "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class<*>;", null);
                            mv.visitCode();
                            // Create ClassReader
                            mv.visitTypeInsn(NEW, "org/objectweb/asm/ClassReader");
                            mv.visitInsn(DUP);
                            // Initialize ClassReader
                            mv.visitVarInsn(ALOAD, 2);
                            mv.visitVarInsn(ILOAD, 3);
                            mv.visitVarInsn(ILOAD, 4);
                            mv.visitMethodInsn(INVOKESPECIAL, "org/objectweb/asm/ClassReader", "<init>", "([BII)V", false);
                            // Save ClassReader
                            mv.visitVarInsn(ASTORE, 6);
                            // Create ClassWriter
                            mv.visitTypeInsn(NEW, "org/objectweb/asm/ClassWriter");
                            mv.visitInsn(DUP);
                            // Initialize ClassWriter against ClassReader (makes it fassssterrrr!)
                            mv.visitVarInsn(ALOAD, 6);
                            mv.visitInsn(ICONST_0);
                            mv.visitMethodInsn(INVOKESPECIAL, "org/objectweb/asm/ClassWriter", "<init>", "(Lorg/objectweb/asm/ClassReader;I)V", false);
                            // Save ClassWriter
                            mv.visitVarInsn(ASTORE, 7);
                            // easier to just explain it in java...
                            // java: ClassReader.accept(ShiftyClassLoader.visitor.delegate(ClassWriter), 0)
                            mv.visitVarInsn(ALOAD, 6);
                            mv.visitFieldInsn(GETSTATIC, "com/sh1ftchg/java9/ShiftyClassLoader", "visitor", "Lcom/sh1ftchg/java9/ShiftyClassLoader$SunAccessVisitor;");
                            mv.visitVarInsn(ALOAD, 7);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "com/sh1ftchg/java9/ShiftyClassLoader$SunAccessVisitor", "delegate", "(Lorg/objectweb/asm/ClassVisitor;)Lorg/objectweb/asm/ClassVisitor;", false);
                            mv.visitInsn(ICONST_0);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "org/objectweb/asm/ClassReader", "accept", "(Lorg/objectweb/asm/ClassVisitor;I)V", false);
                            // get the modified bytecode
                            mv.visitVarInsn(ALOAD, 7);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "org/objectweb/asm/ClassWriter", "toByteArray", "()[B", false);
                            mv.visitVarInsn(ASTORE, 2);
                            // now call the original defineClass as intended, but with our modifications.
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitVarInsn(ALOAD, 1);
                            mv.visitVarInsn(ALOAD, 2);
                            mv.visitInsn(ICONST_0);
                            mv.visitVarInsn(ALOAD, 2);
                            mv.visitInsn(ARRAYLENGTH);
                            mv.visitVarInsn(ALOAD, 5);
                            mv.visitMethodInsn(INVOKESPECIAL, "java/net/URLClassLoader", "defineClass", "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;", false);
                            // return the result
                            mv.visitInsn(ARETURN);
                            // method stuff
                            mv.visitMaxs(6, 8);
                            mv.visitEnd();
                            // now, we're done! finish the class
                            super.visitEnd();
                        }
                    }, 0);
                    // save our modifications before loading
                    bytes = writer.toByteArray();
                } else {
                    ByteArrayOutputStream writer = buffer;
                    writer.reset();
                    while (true) {
                        // Yes this is slow, and heavy, copying a byte at a time... But look at the alternatives, they
                        // all boil down to doing this anyway! So we may as well do it here and relegate some of the
                        // excessive method invocations. I mean... We could use Unsafe - but I've already blown your
                        // mind, getting mods in java 9.

                        // Note: Java 9 actually has a neat little "readAllBytes" expression, but since I'm targeting
                        // forge, I have to use Java 8. Or is it 6? I can't know because they don't even know.
                        int read = in.read();
                        if (read < 0)
                            break;
                        writer.write(read);
                    }
                    bytes = writer.toByteArray();
                }
				
				URL source = null;
                String find = url.toString();
                for (URL boot : bootClassPath) {
                    String path = boot.toString();
                    if (find.startsWith(path)) {
                        if (source != null && source.toString().length() > path.length())
                            continue;
                        source = boot;
                    }
                }
                if (source == null)
                    source = url;

                // Tuh-duh. Java 8's mimicry complete. Now all the hacky things they do and expect are present.
                result = defineClass(name, bytes, 0, bytes.length,
                        new ProtectionDomain(
                                new CodeSource(
                                        source,
                                        (Certificate[]) null
                                ),
                                null,
                                this,
                                null)
                );

                // Cache it.
                loaded.put(name, result);
            } catch (IOException e) {
                // You can't just die Johnny, live on, even if it's not with me!
                result = super.loadClass(name, resolve);
            }
        }
        // like all code, this is self explanatory - i hate documentation
        return result;
    }
}

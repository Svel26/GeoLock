package bittree.geolock;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.Enumeration;
import net.minecraft.server.Bootstrap;

public class DebugInspector {
    public static void main(String[] args) {
        try {
            System.out.println("Scanning classpath for subclasses/subinterfaces of FunctionContext...");
            Class<?> target = Class.forName("net.minecraft.world.level.levelgen.DensityFunction$FunctionContext");
            String classpath = System.getProperty("java.class.path");
            String[] paths = classpath.split(File.pathSeparator);
            for (String path : paths) {
                File file = new File(path);
                if (!file.exists()) continue;
                if (file.isDirectory()) {
                    scanDir(file, "", target);
                } else if (file.getName().endsWith(".jar")) {
                    scanJar(file, target);
                }
            }
        } catch (Throwable e) {
            System.out.println("Error: " + e.toString());
        }
    }

    private static void scanJar(File jarFile, Class<?> target) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    checkClass(className, target);
                }
            }
        } catch (IOException e) {
            // Ignore jar read errors
        }
    }

    private static void scanDir(File dir, String pkg, Class<?> target) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(f, pkg.isEmpty() ? f.getName() : pkg + "." + f.getName(), target);
            } else if (f.getName().endsWith(".class")) {
                String className = pkg + "." + f.getName().substring(0, f.getName().length() - 6);
                checkClass(className, target);
            }
        }
    }

    private static void checkClass(String className, Class<?> target) {
        if (!className.startsWith("net.minecraft") && !className.startsWith("dev.worldgen") && !className.startsWith("tectonic")) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(className, false, DebugInspector.class.getClassLoader());
            boolean hasX = false, hasY = false, hasZ = false;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == double.class) {
                    if (m.getName().equals("x")) hasX = true;
                    if (m.getName().equals("y")) hasY = true;
                    if (m.getName().equals("z")) hasZ = true;
                }
            }
            if (hasX && hasY && hasZ) {
                System.out.println("Match found: " + clazz.getName());
                if (clazz.isInterface()) {
                    System.out.println("  Is Interface!");
                }
                for (Class<?> iface : clazz.getInterfaces()) {
                    System.out.println("  Implements: " + iface.getName());
                }
            }
        } catch (Throwable t) {
            // Ignore linkage or class loading errors
        }
    }
}

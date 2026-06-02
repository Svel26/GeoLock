import java.lang.reflect.*;
import java.net.*;
import java.io.*;
public class DumpClass {
    public static void main(String[] args) throws Exception {
        File file = new File("C:\\Users\\svenj\\.gradle\\caches\\neoformruntime\\artifacts\\minecraft_1.21.1_client.jar");
        URLClassLoader cl = new URLClassLoader(new URL[]{file.toURI().toURL()});
        Class<?> clazz = cl.loadClass("dys$u");
        System.out.println("Fields:");
        for (Field f : clazz.getDeclaredFields()) {
            System.out.println("  " + f.getType().getName() + " " + f.getName());
        }
        System.out.println("Methods:");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println("  " + m.getName() + " " + m.getParameterCount());
        }
    }
}

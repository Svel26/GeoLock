package bittree.geolock;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import java.lang.reflect.Method;

public class Check {
    public static void main(String[] args) {
        System.out.println("--- WorldgenRandom Methods ---");
        for (Method m : WorldgenRandom.class.getMethods()) {
            System.out.println(m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}

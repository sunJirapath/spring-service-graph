import java.lang.annotation.*;
import java.util.*;
import org.springframework.core.annotation.AliasFor;
import org.springframework.core.annotation.MergedAnnotations;

// The two independent attributes mirror EnableFeignClients: neither has @AliasFor.
public class FeignMetadataProbe {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @interface EnableClients {
        String[] value() default {};
        String[] basePackages() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @EnableClients
    @interface Paired {
        @AliasFor(annotation=EnableClients.class, attribute="value")
        String[] value() default {};
        @AliasFor(annotation=EnableClients.class, attribute="basePackages")
        String[] basePackages() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @EnableClients("meta")
    @interface Separate {
        @AliasFor(annotation=EnableClients.class, attribute="basePackages")
        String[] basePackages() default {};
    }
    @Paired("clients") static class ValueOnly {}
    @Paired(basePackages="clients") static class PackagesOnly {}
    @Separate(basePackages="clients") static class MetaAndOverride {}
    public static void main(String[] args) {
        for (Class<?> type : List.of(ValueOnly.class, PackagesOnly.class, MetaAndOverride.class)) {
            var annotation=MergedAnnotations.from(type).get(EnableClients.class);
            String[] value=annotation.getStringArray("value");
            String[] packages=annotation.getStringArray("basePackages");
            Set<String> roots=new LinkedHashSet<>(Arrays.asList(value));
            roots.addAll(Arrays.asList(packages));
            Set<String> expected=type==MetaAndOverride.class ? Set.of("meta","clients") : Set.of("clients");
            if (!roots.equals(expected)) throw new AssertionError(type+": "+roots);
            System.out.println(type.getSimpleName()+" value="+Arrays.toString(value)
                +" basePackages="+Arrays.toString(packages)+" roots="+roots);
        }
    }
}

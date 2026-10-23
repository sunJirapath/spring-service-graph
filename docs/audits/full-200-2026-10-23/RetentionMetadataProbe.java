import java.lang.annotation.*;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

public class RetentionMetadataProbe {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @interface EnableClients {}
    @Retention(RetentionPolicy.SOURCE) @EnableClients @interface SourceComp {}
    @Retention(RetentionPolicy.CLASS) @EnableClients @interface ClassComp {}
    @EnableClients @interface DefaultComp {}
    @Retention(RetentionPolicy.RUNTIME) @EnableClients @interface RuntimeComp {}
    @SourceComp static class SourceUse {}
    @ClassComp static class ClassUse {}
    @DefaultComp static class DefaultUse {}
    @RuntimeComp static class RuntimeUse {}
    public static void main(String[] args) throws Exception {
        var factory = new SimpleMetadataReaderFactory(RetentionMetadataProbe.class.getClassLoader());
        for (Class<?> type : new Class<?>[] { SourceUse.class, ClassUse.class, DefaultUse.class, RuntimeUse.class }) {
            var runtime = MergedAnnotations.from(type).isPresent(EnableClients.class);
            var metadata = factory.getMetadataReader(type.getName()).getAnnotationMetadata();
            var asmMetaAnnotation = metadata.hasMetaAnnotation(EnableClients.class.getName());
            var asmMergedAnnotations = metadata.getAnnotations().isPresent(EnableClients.class);
            boolean expected = type == RuntimeUse.class;
            if (runtime != expected || asmMetaAnnotation != expected || asmMergedAnnotations != expected)
                throw new AssertionError(type.getSimpleName()+": incorrect retention behavior");
            System.out.println(type.getSimpleName() + " reflection=" + runtime + " asmMeta=" + asmMetaAnnotation
                    + " asmMerged=" + asmMergedAnnotations);
        }
    }
}

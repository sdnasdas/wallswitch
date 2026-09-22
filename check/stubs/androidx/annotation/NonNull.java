package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 本地类型检查桩：真实注解来自 androidx.annotation 库。 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE})
public @interface NonNull {
}
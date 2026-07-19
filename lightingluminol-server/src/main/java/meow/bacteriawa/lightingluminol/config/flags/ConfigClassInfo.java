package meow.bacteriawa.lightingluminol.config.flags;

import meow.bacteriawa.lightingluminol.enums.EnumConfigCategory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigClassInfo {
    EnumConfigCategory category();
    String name();
}
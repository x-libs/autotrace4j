package io.github.artlibs.autotrace4j.transformer.impl;

import io.github.artlibs.autotrace4j.transformer.abs.AbsDelegateTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * OkHttp Send Callback增强转换器
 * <p>
 * @author Fury
 * @since 2024-03-30
 * <p>
 * All rights Reserved.
 */
@SuppressWarnings("unused")
public class OkHttp3SendCallbackTransformer extends AbsDelegateTransformer.AbsAnonymousInterface {
    @Override
    public ElementMatcher<? super TypeDescription> typeMatcher() {
        return not(isAbstract()).and(not(isInterface())).and(
                hasSuperType(named("okhttp3.Callback")));
    }

    @Override
    protected ElementMatcher<? super MethodDescription> methodMatcher() {
        return named("onResponse").or(named("onFailure"));
    }
}

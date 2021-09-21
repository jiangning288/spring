/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.type.filter;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * A simple filter which matches classes with a given annotation,
 * checking inherited annotations as well.
 *
 * <p>The matching logic mirrors that of {@link java.lang.Class#isAnnotationPresent(Class)}.
 *
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @author Juergen Hoeller
 * @since 2.5
 */
public class AnnotationTypeFilter extends AbstractTypeHierarchyTraversingFilter {

	private final Class<? extends Annotation> annotationType;

	private final boolean considerMetaAnnotations;


	/**
	 * Create a new AnnotationTypeFilter for the given annotation type.
	 * This filter will also match meta-annotations. To disable the
	 * meta-annotation matching, use the constructor that accepts a
	 * '{@code considerMetaAnnotations}' argument. The filter will
	 * not match interfaces.
	 * @param annotationType the annotation type to match
	 *
     * 通过给定的注解类型创建一个新的注解类型过滤器
	 * 这个过滤器还会匹配元注解
	 * 如果要禁用元注解匹配，可以使用接受considerMetaAnnotations参数的构造方法。
	 * 这个过滤器不会匹配接口
	 */
	public AnnotationTypeFilter(Class<? extends Annotation> annotationType) {
		this(annotationType, true, false);
	}

	/**
	 * Create a new AnnotationTypeFilter for the given annotation type.
	 * The filter will not match interfaces.
	 * @param annotationType the annotation type to match
	 * @param considerMetaAnnotations whether to also match on meta-annotations
	 */
	public AnnotationTypeFilter(Class<? extends Annotation> annotationType, boolean considerMetaAnnotations) {
		this(annotationType, considerMetaAnnotations, false);
	}

	/**
	 * Create a new {@link AnnotationTypeFilter} for the given annotation type.
	 * @param annotationType the annotation type to match
	 * @param considerMetaAnnotations whether to also match on meta-annotations
	 * @param considerInterfaces whether to also match interfaces
	 * 默认considerMetaAnnotations为true，considerInterfaces为false
	 * 这说明注解中如果它的元注解匹配过滤器，那么也算是匹配。
	 * 过滤的时候不考虑是接口的情况
	 */
	public AnnotationTypeFilter(
			Class<? extends Annotation> annotationType, boolean considerMetaAnnotations, boolean considerInterfaces) {

		//isAnnotationPresent方法是用来查看annotationType上是否有Inherited注解
		//其boolean值赋值给父类AbstractTypeHierarchyTraversingFilter的属性considerInherited中
		super(annotationType.isAnnotationPresent(Inherited.class), considerInterfaces);
		this.annotationType = annotationType;
		this.considerMetaAnnotations = considerMetaAnnotations;
	}

	/**
	 * Return the {@link Annotation} that this instance is using to filter
	 * candidates.
	 * @since 5.0
	 */
	public final Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	@Override
	protected boolean matchSelf(MetadataReader metadataReader) {
		AnnotationMetadata metadata = metadataReader.getAnnotationMetadata();
		return metadata.hasAnnotation(this.annotationType.getName()) ||
				(this.considerMetaAnnotations && metadata.hasMetaAnnotation(this.annotationType.getName()));
	}

	@Override
	@Nullable
	protected Boolean matchSuperClass(String superClassName) {
		return hasAnnotation(superClassName);
	}

	@Override
	@Nullable
	protected Boolean matchInterface(String interfaceName) {
		return hasAnnotation(interfaceName);
	}

	@Nullable
	protected Boolean hasAnnotation(String typeName) {
		if (Object.class.getName().equals(typeName)) {
			return false;
		}
		else if (typeName.startsWith("java")) {
			if (!this.annotationType.getName().startsWith("java")) {
				// Standard Java types do not have non-standard annotations on them ->
				// skip any load attempt, in particular for Java language interfaces.
				return false;
			}
			try {
				Class<?> clazz = ClassUtils.forName(typeName, getClass().getClassLoader());
				return ((this.considerMetaAnnotations ? AnnotationUtils.getAnnotation(clazz, this.annotationType) :
						clazz.getAnnotation(this.annotationType)) != null);
			}
			catch (Throwable ex) {
				// Class not regularly loadable - can't determine a match that way.
			}
		}
		return null;
	}

}

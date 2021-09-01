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

package org.springframework.context.annotation;

import java.util.Objects;

import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;

/**
 * A variation of {@link ImportSelector} that runs after all {@code @Configuration} beans
 * have been processed. This type of selector can be particularly useful when the selected
 * imports are {@code @Conditional}.
 *
 * <p>Implementations can also extend the {@link org.springframework.core.Ordered}
 * interface or use the {@link org.springframework.core.annotation.Order} annotation to
 * indicate a precedence against other {@link DeferredImportSelector DeferredImportSelectors}.
 *
 * <p>Implementations may also provide an {@link #getImportGroup() import group} which
 * can provide additional sorting and filtering logic across different selectors.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 4.0
 */

/**
 * 1. DeferredImportSelector是ImportSelector的一个扩展；DeferredImportSelector继承自ImportSelector 接口, 但却并未实现其selectImports方法, 对DeferredImportSelector 子类也不会调用该方法
 * 2. ImportSelector实例的selectImports方法的执行时机，是在@Configguration注解中的其他逻辑被处理之前，所谓的其他逻辑，包括对@ImportResource、@Bean这些注解的处理（注意，这里只是对@Bean修饰的方法的处理，并不是立即调用@Bean修饰的方法，这个区别很重要！）；
 * 3. DeferredImportSelector实例的selectImports方法的执行时机，是在@Configguration注解中的其他逻辑被处理完毕之后，所谓的其他逻辑，包括对@ImportResource、@Bean这些注解的处理；
 * 4. DeferredImportSelector的实现类可以用Order注解，或者实现Ordered接口来对selectImports的执行顺序排序；
 * 5. ImportSelector是Spring3.1提供的，DeferredImportSelector是Spring4.0提供的
 * Spring Boot的自动配置功能就是通过DeferredImportSelector接口的实现类EnableAutoConfigurationImportSelector做到的（因为自动配置必须在我们自定义配置后执行才行）
 *
 * ImportSelector 和DeferredImportSelector 导入的类，全部放到ConfigurationClassParser内部类ImportStack的属性：
 * private final MultiValueMap<String, AnnotationMetadata> imports
 * 但是放入时机不一样，DeferredImportSelector 首次导入时，存放到ConfigurationClassParser内部类DeferredImportSelectorHandler中的属性：
 * private List<ConfigurationClassParser.DeferredImportSelectorHolder> deferredImportSelectors;
 * 执行this.deferredImportSelectorHandler.process();
 * 将deferredImportSelector导入的类放入ConfigurationClassParser内部类ImportStack的属性：
 * private final MultiValueMap<String, AnnotationMetadata> imports
 */
public interface DeferredImportSelector extends ImportSelector {

	/**
	 * Return a specific import group or {@code null} if no grouping is required.
	 * @return the import group class or {@code null}
	 * 要使用DeferredImportSelector 就要实现下面的getImportGroup方法，并要写一个实现Group接口的类，该方法返回一个Class，
	 * 表示当前DeferredImportSelector 属于哪个组的，spring会生成唯一的Group，并将返回值为该Group的DeferredImportSelector放入一个List里
	 */
	@Nullable
	default Class<? extends Group> getImportGroup() {
		return null;
	}


	/**
	 * Interface used to group results from different import selectors.
	 * Group 是DeferredImportSelector 的内部一个接口
	 */
	interface Group {

		/**
		 * Process the {@link AnnotationMetadata} of the importing @{@link Configuration}
		 * class using the specified {@link DeferredImportSelector}.
		 * 上面分组完成后spring会调用该方法，循环List里的DeferredImportSelector 类，并循环调用process方法
		 * AnnotationMetadata :当前循环的DeferredImportSelector 的导入配置类（当前@Import注解的类）

		 */
		void process(AnnotationMetadata metadata, DeferredImportSelector selector);

		/**
		 * Return the {@link Entry entries} of which class(es) should be imported for this
		 * group.
		 * 每个Group只执行一次，返回一个迭代器，spring会使用迭代器的forEach方法进行迭代，
		 * x想要导入spting容器的类要封装成Entry对象，且返回的对象不能为null，会报错（设计问题）
		 */
		Iterable<Entry> selectImports();

		/**
		 * An entry that holds the {@link AnnotationMetadata} of the importing
		 * {@link Configuration} class and the class name to import.
		 */
		class Entry {
			//AnnotationMetadata :必须是一个将DeferredImportSelector 导入的配置类，要不会报错，而且不能new
			private final AnnotationMetadata metadata;
			//importClassName：需要导入类的类路径名
			private final String importClassName;

			public Entry(AnnotationMetadata metadata, String importClassName) {
				this.metadata = metadata;
				this.importClassName = importClassName;
			}

			/**
			 * Return the {@link AnnotationMetadata} of the importing
			 * {@link Configuration} class.
			 */
			public AnnotationMetadata getMetadata() {
				return this.metadata;
			}

			/**
			 * Return the fully qualified name of the class to import.
			 */
			public String getImportClassName() {
				return this.importClassName;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (o == null || getClass() != o.getClass()) {
					return false;
				}
				Entry entry = (Entry) o;
				return Objects.equals(this.metadata, entry.metadata) &&
						Objects.equals(this.importClassName, entry.importClassName);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.metadata, this.importClassName);
			}
		}
	}

}

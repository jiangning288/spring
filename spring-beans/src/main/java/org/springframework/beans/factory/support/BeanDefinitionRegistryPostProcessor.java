/*
 * Copyright 2002-2010 the original author or authors.
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

package org.springframework.beans.factory.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

/**
 * Extension to the standard {@link BeanFactoryPostProcessor} SPI, allowing for
 * the registration of further bean definitions <i>before</i> regular
 * BeanFactoryPostProcessor detection kicks in. In particular,
 * BeanDefinitionRegistryPostProcessor may register further bean definitions
 * which in turn define BeanFactoryPostProcessor instances.
 *
 * @author Juergen Hoeller
 * @since 3.0.1
 * @see org.springframework.context.annotation.ConfigurationClassPostProcessor
 */
/**
 * BeanDefinitionRegistryPostProcessor 和 BeanFactoryPostProcessor的扩展区别
 * 这两个扩展点的触发时机基本类似，都是在BeanDefinition加载之后，Bean实例化之前。
 * 之所以称为基本类似是因为并非完全相同，官方文档对其接口方法的定义有所不同：
 * ——BeanDefinitionRegistryPostProcessor：All regular bean definitions will have been loaded,but no beans will have been instantiated yet.
 *   意思是所有常规bd已经加载完毕，然后可以再添加一些额外的bd。
 * ——postProcessBeanFactory：All bean definitions will have been loaded, but no beans will have been instantiated yet.
 *   所有的bd已经全部加载完毕，然后可以对这些bd做一些属性的修改或者添加工作。
 *
 * 所以官网的建议是BeanDefinitionRegistryPostProcessor用来添加额外的bd，而BeanFactoryPostProcessor用来修改bd。
 *
 * 执行顺序：参考PostProcessorRegistrationDelegate#invokeBeanFactoryPostProcessors方法，从这里可以看出来Spring执行这两个扩展类的先后顺序是这样的：
 *
 * 1.BeanDefinitionRegistryPostProcessor#postProcessBeanDefinitionRegistry方法
 * 2.BeanDefinitionRegistryPostProcessor#postProcessBeanFactory方法
 * 3.BeanFactoryPostProcessor#postProcessBeanFactory方法
 *
 * Spring的设计理念是执行完第一步之后，所有的bd就已经全都创建出来了，这里包含两种的bd：
 * 第一种常规的bd：
 * 这个bd是通过ConfigurationClassPostProcessor扫描出来的，因为ConfigurationClassPostProcessor也是BeanDefinitionRegistryPostProcessor的实现类，且其是最先执行的。
 * 第二种就是我们自定义实现BeanDefinitionRegistryPostProcessor接口后添加的bd：
 *
 */

public interface BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor {

	/**
	 * Modify the application context's internal bean definition registry after its
	 * standard initialization. All regular bean definitions will have been loaded,
	 * but no beans will have been instantiated yet. This allows for adding further
	 * bean definitions before the next post-processing phase kicks in.
	 * @param registry the bean definition registry used by the application context
	 * @throws org.springframework.beans.BeansException in case of errors
	 */
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;

}

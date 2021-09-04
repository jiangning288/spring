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

package org.springframework.context;

import java.util.EventObject;

/**
 * Class to be extended by all application events. Abstract as it
 * doesn't make sense for generic events to be published directly.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 *
 * Spring的Application事件：
 * 1。ContextRefreshedContext：
 *    当容器被实例化或refreshed时发布。如调用refresh()方法,此处的实例化是指所有的bean都已被加载后置处理器都被激活，所有单例bean都已被实例化所有的容器对象都已准备好
 *    可使用如果容器支持热重载则refresh可以被触发多次(XmlWebApplicatonContext支持热刷新而 GenericApplicationContext则不支持)
 * 2。ContextStartedEvent:当容器启动时发布,即调用start()方法,已启用意味着所有的Lifecycle bean都已显式接收到了start信号
 * 3。ContextStoppedEvent:当容器停止时发布,即调用stop0()方法,即所有的Lifecycle bean都已显式接收到了stop信号，关闭的容器可以通过start()方法重启
 * 4。ContextClosedEvent：当容器关闭时发布，即调用close()方法,关闭意味着所有的单例bean都已被销毁关闭的容器不能被重启或refresh()
 * 5。RequestHandledEvent：这只在使用spring的DispatcherServlet时有效,当一个请求被处理完成时发布
 * 6。自定义事件：继承ApplicationEvent
 *
 */
public abstract class ApplicationEvent extends EventObject {

	/** use serialVersionUID from Spring 1.2 for interoperability. */
	private static final long serialVersionUID = 7099057708183571937L;

	/** System time when the event happened. */
	private final long timestamp;


	/**
	 * Create a new ApplicationEvent.
	 * @param source the object on which the event initially occurred (never {@code null})
	 */
	public ApplicationEvent(Object source) {
		super(source);
		this.timestamp = System.currentTimeMillis();
	}


	/**
	 * Return the system time in milliseconds when the event happened.
	 */
	public final long getTimestamp() {
		return this.timestamp;
	}

}

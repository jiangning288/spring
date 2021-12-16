package com.jiangning;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.stereotype.Component;
import sun.tools.tree.Context;

/**
 * @author jiangning04
 * @version 1.0
 * @description: TODO
 * @date 2021/9/4 11:23 下午
 */
//@Component
public class ApplicationStartedEventListener implements ApplicationListener<ContextRefreshedEvent> {

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		System.out.println("=========rrrrr===========");
	}
}

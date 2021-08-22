package com.jiangning;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class UserDao{
	@Autowired
	private ApplicationContext applicationContext;

	public void print(){

		System.out.println(applicationContext.getEnvironment());
	}


}

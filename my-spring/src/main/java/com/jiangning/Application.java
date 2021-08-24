package com.jiangning;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Proxy;


public class Application {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext(AppConfig.class);
		UserDao userDao=ac.getBean(UserDao.class);
		userDao.print();
	}
}

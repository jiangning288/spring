package com.jiangning;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author jiangning04
 * @version 1.0
 * @description: TODO
 * @date 2021/12/2 8:28 下午
 */
@Component
public class A {

	@Autowired
	public void a(B b){
		System.out.println(b);
		System.out.println("-------");
	}
}

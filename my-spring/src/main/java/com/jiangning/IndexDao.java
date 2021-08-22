package com.jiangning;


import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class IndexDao implements Dao {
	@Override
	public void print() {
		System.out.println("IndexDao");
	}
}

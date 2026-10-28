package com.xzwl.cloud.architecture.fixtures.interfacesmybatisviolation.interfaces;

import org.apache.ibatis.session.SqlSession;

public final class SqlSessionAdapter {

    private final SqlSession sqlSession;

    public SqlSessionAdapter(SqlSession sqlSession) {
        this.sqlSession = sqlSession;
    }
}

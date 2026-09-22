package com.project.point.fixture

import org.springframework.test.util.ReflectionTestUtils

fun <T : Any> T.withId(id: Long): T = also { ReflectionTestUtils.setField(it, "id", id) }

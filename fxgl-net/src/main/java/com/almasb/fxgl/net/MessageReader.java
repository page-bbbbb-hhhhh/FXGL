/*
 * FXGL - JavaFX Game Library. The MIT License (MIT).
 * Copyright (c) AlmasB (almaslvl@gmail.com).
 * See LICENSE for details.
 */

package com.almasb.fxgl.net;

/**
 * @author Almas Baimagambetov (almaslvl@gmail.com)
 */
public interface MessageReader<T> {

    T read() throws Exception;
}

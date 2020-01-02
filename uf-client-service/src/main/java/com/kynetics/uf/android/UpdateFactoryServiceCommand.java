/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android;

/**
 * @author Daniele Sergio
 */

public interface UpdateFactoryServiceCommand {
    void authorizationGranted();
    void authorizationDenied();
    void configureService();
}

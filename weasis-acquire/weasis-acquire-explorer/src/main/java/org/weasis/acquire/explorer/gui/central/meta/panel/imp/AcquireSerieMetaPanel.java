/*******************************************************************************
 * Copyright (c) 2016 Weasis Team and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.acquire.explorer.gui.central.meta.panel.imp;

import org.weasis.acquire.explorer.Messages;
import org.weasis.acquire.explorer.core.bean.SeriesGroup;
import org.weasis.acquire.explorer.gui.central.meta.model.AcquireMetadataTableModel;
import org.weasis.acquire.explorer.gui.central.meta.model.imp.AcquireSerieMeta;
import org.weasis.acquire.explorer.gui.central.meta.panel.AcquireMetadataPanel;
import org.weasis.core.api.util.StringUtil;

public class AcquireSerieMetaPanel extends AcquireMetadataPanel {
    private static final long serialVersionUID = -2751941971479265507L;

    private static final String NO_SERIE = Messages.getString("AcquireSerieMetaPanel.no_series"); //$NON-NLS-1$
    private static final String SERIE_PREFIX = Messages.getString("AcquireSerieMetaPanel.series") + StringUtil.COLON_AND_SPACE; //$NON-NLS-1$

    protected SeriesGroup seriesGroup;

    public AcquireSerieMetaPanel(SeriesGroup seriesGroup) {
        super(""); //$NON-NLS-1$
        this.seriesGroup = seriesGroup;

    }

    @Override
    public AcquireMetadataTableModel newTableModel() {
        return new AcquireSerieMeta(seriesGroup);
    }

    @Override
    public String getDisplayText() {
        return (seriesGroup == null) ? NO_SERIE : new StringBuilder(SERIE_PREFIX).append(seriesGroup.getDisplayName()).toString();
    }

    public SeriesGroup getSerie() {
        return seriesGroup;
    }

    public void setSerie(SeriesGroup seriesGroup) {
        this.seriesGroup = seriesGroup;
        this.titleBorder.setTitle(getDisplayText());
        update();
    }

    @Override
    public void update() {
        setMetaVisible(seriesGroup != null);
        super.update();
    }

}

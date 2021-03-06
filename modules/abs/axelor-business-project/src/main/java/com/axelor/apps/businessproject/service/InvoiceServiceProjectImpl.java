/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.businessproject.service;

import java.util.Arrays;
import java.util.List;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.invoice.factory.CancelFactory;
import com.axelor.apps.account.service.invoice.factory.ValidateFactory;
import com.axelor.apps.account.service.invoice.factory.VentilateFactory;
import com.axelor.apps.base.service.alarm.AlarmEngineService;
import com.axelor.apps.businessproject.report.IReport;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.apps.supplychain.service.invoice.InvoiceServiceSupplychainImpl;
import com.axelor.auth.AuthUtils;
import com.axelor.exception.AxelorException;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;

public class InvoiceServiceProjectImpl extends InvoiceServiceSupplychainImpl {

	@Inject
	public InvoiceServiceProjectImpl(ValidateFactory validateFactory, VentilateFactory ventilateFactory,
			CancelFactory cancelFactory, AlarmEngineService<Invoice> alarmEngineService, InvoiceRepository invoiceRepo,
			AppAccountService appAccountService) {
		super(validateFactory, ventilateFactory, cancelFactory, alarmEngineService, invoiceRepo, appAccountService);
	}

	public List<String> editInvoiceAnnex(Invoice invoice, String invoiceIds, boolean toAttach) throws AxelorException{
		
		if (!AuthUtils.getUser().getActiveCompany().getAccountConfig().getDisplayTimesheetOnPrinting() && !AuthUtils.getUser().getActiveCompany().getAccountConfig().getDisplayExpenseOnPrinting()) { return null; }
		
		String language = ReportSettings.getPrintingLocale(invoice.getPartner());

		String title = I18n.get("Invoice");
		if(invoice.getInvoiceId() != null) {
			title += invoice.getInvoiceId();
		}
		
		Integer invoicesCopy = invoice.getInvoicesCopySelect();	
		ReportSettings rS = ReportFactory.createReport(IReport.INVOICE_ANNEX, title + "-" + I18n.get("Annex") + "-${date}");
		
		if (toAttach) {
			rS.toAttach(invoice);
		}
		
		String fileLink = rS.addParam("InvoiceId", invoiceIds)
				.addParam("Locale", language)
				.addParam("InvoicesCopy", invoicesCopy)
				.generate()
				.getFileLink();
		
		List<String> res = Arrays.asList(title, fileLink);
		
		return res;
	}

	
}

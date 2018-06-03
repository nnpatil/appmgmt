/**
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
package com.axelor.apps.account.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Query;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.AnalyticMoveLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountingReportRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.db.repo.ReconcileRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.move.MoveLineService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.app.AppBaseServiceImpl;
import com.axelor.apps.tool.file.CsvTool;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class MoveLineExportServiceImpl implements MoveLineExportService{

	private final Logger log = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	protected AppAccountService appAccountService;

	protected AccountingReportService accountingReportService;
	protected SequenceService sequenceService;
	protected AccountConfigService accountConfigService;
	protected MoveRepository moveRepo;
	protected MoveLineRepository moveLineRepo;
	protected AccountingReportRepository accountingReportRepo;
	protected JournalRepository journalRepo;
	protected AccountRepository accountRepo;
	protected MoveLineService moveLineService;
	protected PartnerService partnerService;
	
	protected static final String DATE_FORMAT_YYYYMMDD = "YYYYMMdd";
	protected static final String DATE_FORMAT_YYYYMMDDHHMMSS = "YYYYMMddHHmmss";

	@Inject
	public MoveLineExportServiceImpl(AppAccountService appAccountService, AccountingReportService accountingReportService, SequenceService sequenceService,
			AccountConfigService accountConfigService, MoveRepository moveRepo, MoveLineRepository moveLineRepo, AccountingReportRepository accountingReportRepo,
			JournalRepository journalRepo, AccountRepository accountRepo, MoveLineService moveLineService, PartnerService partnerService) {
		this.accountingReportService = accountingReportService;
		this.sequenceService = sequenceService;
		this.accountConfigService = accountConfigService;
		this.moveRepo = moveRepo;
		this.moveLineRepo = moveLineRepo;
		this.accountingReportRepo = accountingReportRepo;
		this.journalRepo = journalRepo;
		this.accountRepo = accountRepo;
		this.moveLineService = moveLineService;
		this.partnerService = partnerService;
		this.appAccountService = appAccountService;
	}


	public void updateMoveList(List<Move> moveList, AccountingReport accountingReport, LocalDate localDate, String exportToAgressoNumber)  {

		int i = 0;

		int moveListSize = moveList.size();

		for(Move move : moveList)  {

			this.updateMove(moveRepo.find(move.getId()), accountingReportRepo.find(accountingReport.getId()), localDate, exportToAgressoNumber);

			if (i % 10 == 0) { JPA.clear(); }
			if (i++ % 100 == 0) { log.debug("Process : {} / {}" , i, moveListSize); }

		}

	}


	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public Move updateMove(Move move, AccountingReport accountingReport, LocalDate localDate, String exportToAgressoNumber)  {

		move.setExportNumber(exportToAgressoNumber);
		move.setExportDate(localDate);
		move.setAccountingOk(true);
		move.setAccountingReport(accountingReport);
		moveRepo.save(move);

		return move;
	}


	public BigDecimal getSumDebit(String queryFilter, List<? extends Move> moveList)  {

		Query q = JPA.em().createQuery("select SUM(self.debit) FROM MoveLine as self WHERE " + queryFilter, BigDecimal.class);
		q.setParameter(1, moveList);

		BigDecimal result = (BigDecimal) q.getSingleResult();
		log.debug("Total debit : {}", result);

		if(result != null)  {
			return result;
		}
		else  {
			return BigDecimal.ZERO;
		}

	}

	public BigDecimal getSumCredit(String queryFilter, List<Move> moveList)  {

		Query q = JPA.em().createQuery("select SUM(self.credit) FROM MoveLine as self WHERE " + queryFilter, BigDecimal.class);
		q.setParameter(1, moveList);

		BigDecimal result = (BigDecimal) q.getSingleResult();
		log.debug("Total credit : {}", result);

		if(result != null)  {
			return result;
		}
		else  {
			return BigDecimal.ZERO;
		}

	}


	public BigDecimal getSumCredit(List<MoveLine> moveLineList)  {

		BigDecimal sumCredit = BigDecimal.ZERO;
		for(MoveLine moveLine : moveLineList) {
			sumCredit = sumCredit.add(moveLine.getCredit());
		}

		return sumCredit;
	}



	public BigDecimal getTotalAmount(List<MoveLine> moveLinelst) {

		BigDecimal totDebit = BigDecimal.ZERO;
		BigDecimal totCredit = BigDecimal.ZERO;

		for(MoveLine moveLine : moveLinelst) {
			totDebit = totDebit.add(moveLine.getDebit());
			totCredit = totCredit.add(moveLine.getCredit());
		}

		return totCredit.subtract(totDebit);
	}


	public String getSaleExportNumber(Company company) throws AxelorException  {

		String exportNumber = sequenceService.getSequenceNumber(SequenceRepository.SALES_INTERFACE, company);
		if(exportNumber == null)  {
			throw new AxelorException(company, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MOVE_LINE_EXPORT_1), AppBaseServiceImpl.EXCEPTION, company.getName());
		}

		return exportNumber;

	}


	public String getRefundExportNumber(Company company) throws AxelorException  {

		String exportNumber = sequenceService.getSequenceNumber(SequenceRepository.REFUND_INTERFACE, company);
		if(exportNumber == null)  {
			throw new AxelorException(company, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MOVE_LINE_EXPORT_2), AppBaseServiceImpl.EXCEPTION, company.getName());
		}

		return exportNumber;

	}


	public String getTreasuryExportNumber(Company company) throws AxelorException  {

		String exportNumber = sequenceService.getSequenceNumber(SequenceRepository.TREASURY_INTERFACE, company);
		if(exportNumber == null)  {
			throw new AxelorException(company, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MOVE_LINE_EXPORT_3), AppBaseServiceImpl.EXCEPTION, company.getName());
		}

		return exportNumber;

	}


	public String getPurchaseExportNumber(Company company) throws AxelorException  {

		String exportNumber = sequenceService.getSequenceNumber(SequenceRepository.PURCHASE_INTERFACE, company);
		if(exportNumber == null)  {
			throw new AxelorException(company, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MOVE_LINE_EXPORT_4), AppBaseServiceImpl.EXCEPTION, company.getName());
		}

		return exportNumber;

	}


	/**
	 * Méthode réalisant l'export SI - Agresso pour les journaux de type vente
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	public void exportMoveLineTypeSelect1006(AccountingReport mlr, boolean replay) throws AxelorException, IOException {

		log.info("In Export type service : ");

		String fileName = "detail"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"ventes.dat";
		this.exportMoveLineTypeSelect1006FILE1(mlr, replay);
		this.exportMoveLineAllTypeSelectFILE2(mlr,fileName);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso des en-têtes pour les journaux de type vente
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void exportMoveLineTypeSelect1006FILE1(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In export service Type 1006 FILE 1 :");

		Company company = accountingReport.getCompany();

		String dateQueryStr = String.format(" WHERE self.company = %s", company.getId());
		JournalType journalType = accountingReportService.getJournalType(accountingReport);
		if(accountingReport.getJournal()!=null) {
			dateQueryStr += String.format(" AND self.journal = %s", accountingReport.getJournal().getId());
		}
		else  {
			dateQueryStr += String.format(" AND self.journal.journalType = %s", journalType.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			dateQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(replay)  {
			dateQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			dateQueryStr += " AND self.accountingOk = false ";
		}
		dateQueryStr += " AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false ";
		dateQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);
		Query dateQuery = JPA.em().createQuery("SELECT self.date from Move self" + dateQueryStr + "group by self.date order by self.date");

		List<LocalDate> allDates = dateQuery.getResultList();

		log.debug("allDates : {}" , allDates);

		List<String[]> allMoveData = new ArrayList<String[]>();
		String companyCode = "";

		String reference = "";
		String moveQueryStr = "";
		String moveLineQueryStr = "";
		if(accountingReport.getRef()!=null) {
			reference = accountingReport.getRef();
		}
		if(company != null) {
			companyCode = company.getCode();
			moveQueryStr += String.format(" AND self.company = %s", company.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			moveQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(accountingReport.getDateFrom() != null)  {
			moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
		}
		if(accountingReport.getDateTo() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}
		if(replay)  {
			moveQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			moveQueryStr += " AND self.accountingOk = false ";
		}
		moveQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);

		LocalDate interfaceDate = accountingReport.getDate();

		for(LocalDate dt : allDates) {

			List<Journal> journalList = journalRepo.all().filter("self.journalType = ?1 AND self.notExportOk = false", journalType).fetch();

			if(accountingReport.getJournal() != null)  {
				journalList = new ArrayList<Journal>();
				journalList.add(accountingReport.getJournal());
			}

			for(Journal journal : journalList)  {

				List<? extends Move> moveList = moveRepo.all().filter("self.date = ?1 AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false AND self.journal = ?2" + moveQueryStr, dt, journal).fetch();

				String journalCode = journal.getExportCode();

				if (moveList.size() > 0) {

					BigDecimal sumDebit = this.getSumDebit("self.account.useForPartnerBalance = true AND self.debit != 0.00 AND self.move in ?1 "+ moveLineQueryStr, moveList);

					if(sumDebit.compareTo(BigDecimal.ZERO) == 1)  {

						String exportNumber = this.getSaleExportNumber(company);

						Move firstMove = moveList.get(0);
						String periodCode = firstMove.getPeriod().getFromDate().format(DateTimeFormatter.ofPattern("yyyyMM"));

						this.updateMoveList((List<Move>) moveList, accountingReport, interfaceDate, exportNumber);

						String items[] = new String[8];
						items[0] = companyCode;
						items[1] = journalCode;
						items[2] = exportNumber;
						items[3] = interfaceDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[4] = sumDebit.toString();
						items[5] = reference;
						items[6] = dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[7]= periodCode;
						allMoveData.add(items);
					}
				}
			}
		}

		String fileName = "entete"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"ventes.dat";
		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		new File(filePath).mkdirs();

		log.debug("Full path to export : {}{}" , filePath, fileName);
		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveData);
		// Utilisé pour le debuggage
//			CsvTool.csvWriter(filePath, fileName, '|', this.createHeaderForHeaderFile(mlr.getTypeSelect()), allMoveData);
	}



	/**
	 * Méthode réalisant l'export SI - Agresso pour les journaux de type avoir
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	public void exportMoveLineTypeSelect1007(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In Export type 1007 service : ");

		String fileName = "detail"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"avoirs.dat";
		this.exportMoveLineTypeSelect1007FILE1(accountingReport, replay);
		this.exportMoveLineAllTypeSelectFILE2(accountingReport, fileName);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso des en-têtes pour les journaux de type avoir
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void exportMoveLineTypeSelect1007FILE1(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In export service 1007 FILE 1:");

		Company company = accountingReport.getCompany();

		String dateQueryStr = String.format(" WHERE self.company = %s", company.getId());
		JournalType journalType = accountingReportService.getJournalType(accountingReport);
		if(accountingReport.getJournal()!=null) {
			dateQueryStr += String.format(" AND self.journal = %s", accountingReport.getJournal().getId());
		}
		else  {
			dateQueryStr += String.format(" AND self.journal.journalType = %s", journalType.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			dateQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(replay)  {
			dateQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			dateQueryStr += " AND self.accountingOk = false ";
		}
		dateQueryStr += " AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false ";
		dateQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);
		Query dateQuery = JPA.em().createQuery("SELECT self.date from Move self" + dateQueryStr + "group by self.date order by self.date");

		List<LocalDate> allDates = new ArrayList<LocalDate>();
		allDates = dateQuery.getResultList();

		log.debug("allDates : {}" , allDates);

		List<String[]> allMoveData = new ArrayList<String[]>();
		String companyCode = "";

		String reference = "";
		String moveQueryStr = "";
		String moveLineQueryStr = "";
		if(accountingReport.getRef()!=null) {
			reference = accountingReport.getRef();
		}
		if(accountingReport.getCompany()!=null) {
			companyCode = accountingReport.getCompany().getCode();
			moveQueryStr += String.format(" AND self.company = %s", accountingReport.getCompany().getId());
		}
		if(accountingReport.getPeriod() != null)	{
			moveQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(accountingReport.getDateFrom() != null)  {
			moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
		}
		if(accountingReport.getDateTo() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}
		if(replay)  {
			moveQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			moveQueryStr += " AND self.accountingOk = false ";
		}
		moveQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);

		LocalDate interfaceDate = accountingReport.getDate();

		for(LocalDate dt : allDates) {

			List<Journal> journalList = journalRepo.all().filter("self.journalType = ?1 AND self.notExportOk = false", journalType).fetch();

			if(accountingReport.getJournal()!=null)  {
				journalList = new ArrayList<Journal>();
				journalList.add(accountingReport.getJournal());
			}

			for(Journal journal : journalList)  {

				List<Move> moveList = moveRepo.all().filter("self.date = ?1 AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false AND self.journal = ?2" + moveQueryStr, dt, journal).fetch();

				String journalCode = journal.getExportCode();

				if (moveList.size() > 0) {

					BigDecimal sumCredit = this.getSumCredit("self.account.useForPartnerBalance = true AND self.credit != 0.00 AND self.move in ?1 "+moveLineQueryStr, moveList);

					if(sumCredit.compareTo(BigDecimal.ZERO) == 1)  {

						String exportNumber = this.getSaleExportNumber(company);

						Move firstMove = moveList.get(0);
						String periodCode = firstMove.getPeriod().getFromDate().format(DateTimeFormatter.ofPattern("yyyyMM"));

						this.updateMoveList(moveList, accountingReport, interfaceDate, exportNumber);

						String items[] = new String[8];
						items[0] = companyCode;
						items[1] = journalCode;
						items[2] = exportNumber;
						items[3] = interfaceDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[4] = sumCredit.toString();
						items[5] = reference;
						items[6] = dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[7]= periodCode;
						allMoveData.add(items);
					}
				}
			}
		}

		String fileName = "entete"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"avoirs.dat";
		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		new File(filePath).mkdirs();

		log.debug("Full path to export : {}{}" , filePath, fileName);
		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveData);
		// Utilisé pour le debuggage
//			CsvTool.csvWriter(filePath, fileName, '|', this.createHeaderForHeaderFile(mlr.getTypeSelect()), allMoveData);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso pour les journaux de type trésorerie
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	public void exportMoveLineTypeSelect1008(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In Export type 1008 service : ");

		String fileName = "detail"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"tresorerie.dat";
		this.exportMoveLineTypeSelect1008FILE1(accountingReport, replay);
		this.exportMoveLineAllTypeSelectFILE2(accountingReport, fileName);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso des en-têtes pour les journaux de type trésorerie
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void exportMoveLineTypeSelect1008FILE1(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In export service 1008 FILE 1:");

		Company company = accountingReport.getCompany();

		String dateQueryStr = String.format(" WHERE self.company = %s", company.getId());
		JournalType journalType = accountingReportService.getJournalType(accountingReport);
		if(accountingReport.getJournal()!=null) {
			dateQueryStr += String.format(" AND self.journal = %s", accountingReport.getJournal().getId());
		}
		else  {
			dateQueryStr += String.format(" AND self.journal.journalType = %s", journalType.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			dateQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(replay)  {
			dateQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			dateQueryStr += " AND self.accountingOk = false ";
		}
		dateQueryStr += " AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false ";
		dateQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);
		Query dateQuery = JPA.em().createQuery("SELECT self.date from Move self" + dateQueryStr + "group by self.date order by self.date");

		List<LocalDate> allDates = new ArrayList<LocalDate>();
		allDates = dateQuery.getResultList();

		log.debug("allDates : {}" , allDates);

		List<String[]> allMoveData = new ArrayList<String[]>();
		String companyCode = "";

		String reference = "";
		String moveQueryStr = "";
		String moveLineQueryStr = "";
		if(accountingReport.getRef()!=null) {
			reference = accountingReport.getRef();
		}
		if(company != null) {
			companyCode = accountingReport.getCompany().getCode();
			moveQueryStr += String.format(" AND self.company = %s", company.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			moveQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(accountingReport.getDateFrom() != null)  {
			moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
		}
		if(accountingReport.getDateTo() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}
		if(replay)  {
			moveQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			moveQueryStr += " AND self.accountingOk = false ";
		}
		moveQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);

		LocalDate interfaceDate = accountingReport.getDate();

		for(LocalDate dt : allDates) {

			List<Journal> journalList = journalRepo.all().filter("self.journalType = ?1 AND self.notExportOk = false", journalType).fetch();

			if(accountingReport.getJournal()!=null)  {
				journalList = new ArrayList<Journal>();
				journalList.add(accountingReport.getJournal());
			}

			for(Journal journal : journalList)  {

				List<Move> moveList = moveRepo.all().filter("self.date = ?1 AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false AND self.journal = ?2" + moveQueryStr, dt, journal).fetch();

				String journalCode = journal.getExportCode();

				if (moveList.size() > 0) {

					long moveLineListSize = moveLineRepo.all().filter("self.move in ?1 AND (self.debit > 0 OR self.credit > 0) " + moveLineQueryStr, moveList).count();

					if(moveLineListSize > 0) {

						String exportNumber = this.getTreasuryExportNumber(company);

						Move firstMove = moveList.get(0);
						String periodCode = firstMove.getPeriod().getFromDate().format(DateTimeFormatter.ofPattern("yyyyMM"));

						this.updateMoveList(moveList, accountingReport, interfaceDate, exportNumber);

						String items[] = new String[8];
						items[0] = companyCode;
						items[1] = journalCode;
						items[2] = exportNumber;
						items[3] = interfaceDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[4] = "0";
						items[5] = reference;
						items[6] = dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
						items[7]= periodCode;
						allMoveData.add(items);
					}
				}
			}
		}

		String fileName = "entete"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"tresorerie.dat";
		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		new File(filePath).mkdirs();

		log.debug("Full path to export : {}{}" , filePath, fileName);
		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveData);
		// Utilisé pour le debuggage
//			CsvTool.csvWriter(filePath, fileName, '|', this.createHeaderForHeaderFile(mlr.getTypeSelect()), allMoveData);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso pour les journaux de type achat
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	public void exportMoveLineTypeSelect1009(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In Export type 1009 service : ");
		String fileName = "detail"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"achats.dat";
		this.exportMoveLineTypeSelect1009FILE1(accountingReport, replay);
		this.exportMoveLineAllTypeSelectFILE2(accountingReport, fileName);
	}


	/**
	 * Méthode réalisant l'export SI - Agresso des en-têtes pour les journaux de type achat
	 * @param mlr
	 * @param replay
	 * @throws AxelorException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void exportMoveLineTypeSelect1009FILE1(AccountingReport accountingReport, boolean replay) throws AxelorException, IOException {

		log.info("In export service 1009 FILE 1:");

		Company company = accountingReport.getCompany();
		String dateQueryStr = String.format(" WHERE self.company = %s", company.getId());
		JournalType journalType = accountingReportService.getJournalType(accountingReport);
		if(accountingReport.getJournal() != null) {
			dateQueryStr += String.format(" AND self.journal = %s", accountingReport.getJournal().getId());
		}
		else  {
			dateQueryStr += String.format(" AND self.journal.journalType = %s", journalType.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			dateQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(replay)  {
			dateQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			dateQueryStr += " AND self.accountingOk = false ";
		}
		dateQueryStr += " AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false ";
		dateQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);
		Query dateQuery = JPA.em().createQuery("SELECT self.date from Move self" + dateQueryStr + "group by self.date order by self.date");

		List<LocalDate> allDates = new ArrayList<LocalDate>();
		allDates = dateQuery.getResultList();

		log.debug("allDates : {}" , allDates);

		List<String[]> allMoveData = new ArrayList<String[]>();
		String companyCode = "";

		String reference = "";
		String moveQueryStr = "";
		String moveLineQueryStr = "";
		if(accountingReport.getRef()!=null) {
			reference = accountingReport.getRef();
		}
		if(company != null) {
			companyCode = company.getCode();
			moveQueryStr += String.format(" AND self.company = %s", company.getId());
		}
		if(accountingReport.getPeriod() != null)	{
			moveQueryStr += String.format(" AND self.period = %s", accountingReport.getPeriod().getId());
		}
		if(accountingReport.getDateFrom() != null)  {
			moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
		}
		if(accountingReport.getDateTo() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}
		if(replay)  {
			moveQueryStr += String.format(" AND self.accountingOk = true AND self.accountingReport = %s", accountingReport.getId());
		}
		else  {
			moveQueryStr += " AND self.accountingOk = false ";
		}
		moveQueryStr += String.format(" AND self.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);

		LocalDate interfaceDate = accountingReport.getDate();

		for(LocalDate dt : allDates) {

			List<Journal> journalList = journalRepo.all().filter("self.journalType = ?1 AND self.notExportOk = false", journalType).fetch();

			if(accountingReport.getJournal()!=null)  {
				journalList = new ArrayList<Journal>();
				journalList.add(accountingReport.getJournal());
			}

			for(Journal journal : journalList)  {

				List<Move> moveList = moveRepo.all().filter("self.date = ?1 AND self.ignoreInAccountingOk = false AND self.journal.notExportOk = false AND self.journal = ?2" + moveQueryStr, dt, journal).fetch();

				String journalCode = journal.getExportCode();

				int moveListSize = moveList.size();

				if (moveListSize > 0) {

					int i = 0;

					for(Move move : moveList)  {

						List<MoveLine> moveLineList = moveLineRepo.all().filter("self.account.useForPartnerBalance = true AND self.credit != 0.00 AND self.move in ?1" + moveLineQueryStr, moveList).fetch();

						if(moveLineList.size() > 0) {

							String exportNumber = this.getPurchaseExportNumber(company);

							String periodCode = move.getPeriod().getFromDate().format(DateTimeFormatter.ofPattern("yyyyMM"));

							BigDecimal totalCredit = this.getSumCredit(moveLineList);
							String invoiceId = "";
							String dueDate = "";
							if(move.getInvoice() != null)  {
								invoiceId = move.getInvoice().getInvoiceId();
								dueDate = move.getInvoice().getDueDate().toString();
							}

							MoveLine firstMoveLine = moveLineList.get(0);
							String items[] = new String[11];
							items[0] = companyCode;
							items[1] = journalCode;
							items[2] = exportNumber;
							items[3] = interfaceDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
							items[4] = invoiceId;
							items[5] = dueDate;
							items[6]= firstMoveLine.getAccount().getCode();
							items[7]= totalCredit.toString();
							items[8]= reference;
							items[9]= dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
							items[10]= periodCode;
							allMoveData.add(items);

							this.updateMove(move, accountingReport, interfaceDate, exportNumber);

							if (i % 10 == 0) { JPA.clear(); }
							if (i++ % 100 == 0) { log.debug("Process : {} / {}" , i, moveListSize); }
						}
					}
				}
			}
		}

		String fileName = "entete"+appAccountService.getTodayDateTime().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDDHHMMSS))+"achats.dat";
		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		new File(filePath).mkdirs();

		log.debug("Full path to export : {}{}" , filePath, fileName);
		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveData);
		// Utilisé pour le debuggage
//			CsvTool.csvWriter(filePath, fileName, '|', this.createHeaderForHeaderFile(mlr.getTypeSelect()), allMoveData);
	}

	@Override
	@Transactional(rollbackOn = { AxelorException.class, Exception.class })
	public void exportMoveLineTypeSelect1010(AccountingReport accountingReport) throws AxelorException, IOException {
		log.info("In Export type 1010 service:");
		List<String[]> allMoveLineData = new ArrayList<>();
		String filterStr = accountingReportService.getMoveLineList(accountingReport);
		String queryStr = String.format("SELECT self.accountCode, self.accountName, SUM(self.debit), SUM(self.credit) "
				+ "FROM MoveLine self WHERE %s "
				+ "GROUP BY self.accountCode, self.accountName ORDER BY self.accountCode", filterStr);
		Query query = JPA.em().createQuery(queryStr);

		@SuppressWarnings("unchecked")
		List<Object[]> resultList = query.getResultList();

		for (Object[] result : resultList) {
			String[] items = new String[result.length];
			for (int i = 0; i < result.length; ++i) {
				items[i] = String.valueOf(result[i]);
			}
			allMoveLineData.add(items);
		}

		String filePath = accountConfigService
				.getExportPath(accountConfigService.getAccountConfig(accountingReport.getCompany()));
		LocalDate date;

		if (accountingReport.getDateTo() != null) {
			date = accountingReport.getDateTo();
		} else if (accountingReport.getPeriod() != null) {
			date = accountingReport.getPeriod().getToDate();
		} else {
			date = null;
		}

		String dateStr = date != null ? "-" + date : "";

		String fileName = String.format("%s %s%s.csv", I18n.get("General balance"), accountingReport.getRef(), dateStr);
		Files.createDirectories(Paths.get(filePath));
		Path path = Paths.get(filePath, fileName);

		log.debug("Full path to export: {}", path);
		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveLineData);

		try (InputStream is = new FileInputStream(path.toFile())) {
			Beans.get(MetaFiles.class).attach(is, fileName, accountingReport);
		}
	}

	/**
	* Méthode réalisant l'export des FEC (Fichiers des écritures Comptables)
	* @throws AxelorException
	* @throws IOException
	*/
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void exportMoveLineTypeSelect1000(AccountingReport accountingReport, boolean administration, boolean replay) throws AxelorException, IOException {
		
		log.info("In Export type 1000 service : ");
		List<String[]> allMoveLineData = new ArrayList<>();
		Company company = accountingReport.getCompany();
		
		LocalDate interfaceDate = accountingReport.getDate();
		String exportNumber = this.getSaleExportNumber(company);

		String moveLineQueryStr = "";
		moveLineQueryStr += String.format(" AND self.move.company = %s", company.getId());
		if(accountingReport.getYear() != null){
			moveLineQueryStr += String.format(" AND self.move.period.year = %s", accountingReport.getYear().getId());
		}
		
		if(accountingReport.getPeriod() != null)	{
			moveLineQueryStr += String.format(" AND self.move.period = %s", accountingReport.getPeriod().getId());
		}
		else {
			if (accountingReport.getDateFrom() != null){
				moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
			}
			if (accountingReport.getDateTo() != null){
				moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
			}
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}

		moveLineQueryStr += " AND self.move.ignoreInAccountingOk = false";

		if(!administration){
			moveLineQueryStr += " AND self.move.journal.notExportOk = false";

			if(replay){
				moveLineQueryStr += String.format(" AND self.move.accountingOk = true AND self.move.accountingReport.id = %s",
						accountingReport.getId());
			}
			else{
				moveLineQueryStr += " AND self.move.accountingOk = false";
			}
		}

		List<MoveLine> moveLineList = moveLineRepo.all().filter("self.move.statusSelect = ?1" + moveLineQueryStr, MoveRepository.STATUS_VALIDATED).order("date").order("name").fetch();
		if(!moveLineList.isEmpty()) {
			List<Move> moveList = new ArrayList<>();
			for (MoveLine moveLine : moveLineList) {
				String[] items = new String[18];
				Move move = moveLine.getMove();
				if(!moveList.contains(move)){
					moveList.add(move);
				}
				Journal journal = move.getJournal();
				items[0] = journal.getCode();
				items[1] = journal.getName();
				items[2] = moveLine.getName();
				items[3] = moveLine.getDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD));
				items[4] = moveLine.getAccount().getCode();
				items[5] = moveLine.getAccount().getName();
				items[6] = "";
				items[7] = "";
				Partner partner = moveLine.getPartner();
				if(partner != null)  {
					items[6] = partner.getPartnerSeq();
					items[7] = partner.getFullName();
				}
				items[8] = moveLine.getOrigin();
				items[9] = moveLine.getDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD)); // Pour le moment on va utiliser la date des lignes d'écriture.
				items[10]= moveLine.getDescription();
				items[11]= moveLine.getDebit().toString();
				items[12]= moveLine.getCredit().toString();
				if(moveLine.getDebit().compareTo(BigDecimal.ZERO) > 0){
					List<String> reconcileSeqList = new ArrayList<>();
					List<String> reconcileDateList = new ArrayList<>();

					for (Reconcile reconcile : moveLine.getDebitReconcileList()) {
						reconcileSeqList.add(reconcile.getReconcileSeq());
						reconcileDateList.add(reconcile.getReconciliationDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD)));
					}
					items[13] = StringUtils.join(reconcileSeqList, "; ");
					items[14] = StringUtils.join(reconcileDateList, "; ");
				}
				else{
					List<String> reconcileSeqList = new ArrayList<>();
					List<String> reconcileDateList = new ArrayList<>();
					for (Reconcile reconcile : moveLine.getCreditReconcileList()) {
						if (reconcile.getStatusSelect() == ReconcileRepository.STATUS_CONFIRMED) {
							reconcileSeqList.add(reconcile.getReconcileSeq());
							reconcileDateList.add(reconcile.getReconciliationDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD)));
						}
					}
					items[13] = StringUtils.join(reconcileSeqList, "; ");
					items[14] = StringUtils.join(reconcileDateList, "; ");
				}
				items[15]= move.getValidationDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD));
				items[16]= moveLine.getCurrencyAmount().toString();
				if(move.getCurrency() != null){
					items[17]= move.getCurrency().getCode();
				}
				allMoveLineData.add(items);
			}

			if(!administration){
				this.updateMoveList(moveList, accountingReport, interfaceDate, exportNumber);
			}
		}

		accountingReport = accountingReportRepo.find(accountingReport.getId());
		
		String fileName = this.setFileName(accountingReport);
		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		//TODO create a template Helper
		
		new File(filePath).mkdirs();
		log.debug("Full path to export : {}{}" , filePath, fileName);
//		CsvTool.csvWriter(filePath, fileName, '|', null, allMoveLineData);
		CsvTool.csvWriter(filePath, fileName, '|', this.createHeaderForPayrollJournalEntry(), allMoveLineData);
		accountingReportRepo.save(accountingReport);

		Path path = Paths.get(filePath+fileName);
		
		try (InputStream is = new FileInputStream(path.toFile())) {
			Beans.get(MetaFiles.class).attach(is, fileName, accountingReport);
		}

	}


	/**
	 * Méthode réalisant l'export SI - Agresso des fichiers détails
	 * @param mlr
	 * @param fileName
	 * @throws AxelorException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	public void exportMoveLineAllTypeSelectFILE2(AccountingReport accountingReport, String fileName) throws AxelorException, IOException {

		log.info("In export service FILE 2 :");

		Company company = accountingReport.getCompany();

		String companyCode = "";
		String moveLineQueryStr = "";

		int typeSelect = accountingReport.getTypeSelect();

		if(company != null) {
			companyCode = company.getCode();
			moveLineQueryStr += String.format(" AND self.move.company = %s", company.getId());
		}
		if(accountingReport.getJournal() != null)	{
			moveLineQueryStr += String.format(" AND self.move.journal = %s", accountingReport.getJournal().getId());
		}
		else  {
			moveLineQueryStr += String.format(" AND self.move.journal.journalType = %s", accountingReportService.getJournalType(accountingReport).getId());
		}

		if(accountingReport.getPeriod() != null)	{
			moveLineQueryStr += String.format(" AND self.move.period = %s", accountingReport.getPeriod().getId());
		}
		if(accountingReport.getDateFrom() != null)  {
			moveLineQueryStr += String.format(" AND self.date >= '%s'", accountingReport.getDateFrom().toString());
		}

		if(accountingReport.getDateTo() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDateTo().toString());
		}
		if(accountingReport.getDate() != null)  {
			moveLineQueryStr += String.format(" AND self.date <= '%s'", accountingReport.getDate().toString());
		}
		if(typeSelect != 8 )  {
			moveLineQueryStr += String.format(" AND self.account.useForPartnerBalance = false ");
		}
		moveLineQueryStr += String.format("AND self.move.accountingOk = true AND self.move.ignoreInAccountingOk = false AND self.move.accountingReport = %s", accountingReport.getId());
		moveLineQueryStr += String.format(" AND self.move.statusSelect = %s ", MoveRepository.STATUS_VALIDATED);

		Query queryDate = JPA.em().createQuery("SELECT self.date from MoveLine self where self.account != null AND (self.debit > 0 OR self.credit > 0) " + moveLineQueryStr + " group by self.date ORDER BY self.date");

		List<LocalDate> dates = new ArrayList<LocalDate>();
		dates = queryDate.getResultList();

		log.debug("dates : {}" , dates);

		List<String[]> allMoveLineData = new ArrayList<String[]>();

		for (LocalDate localDate : dates)  {

			Query queryExportAgressoRef = JPA.em().createQuery("SELECT DISTINCT self.move.exportNumber from MoveLine self where self.account != null " +
					"AND (self.debit > 0 OR self.credit > 0) AND self.date = '"+ localDate.toString() +"'"+ moveLineQueryStr);
			List<String> exportAgressoRefs = new ArrayList<String>();
			exportAgressoRefs = queryExportAgressoRef.getResultList();
			for(String exportAgressoRef : exportAgressoRefs)  {

				if(exportAgressoRef != null && !exportAgressoRef.isEmpty())  {

					int sequence = 1;

					Query query = JPA.em().createQuery("SELECT self.account.id from MoveLine self where self.account != null AND (self.debit > 0 OR self.credit > 0) " +
							"AND self.date = '"+ localDate.toString() +"' AND self.move.exportNumber = '"+ exportAgressoRef + "'" + moveLineQueryStr +
							" group by self.account.id");

					List<Long> accountIds = new ArrayList<Long>();
					accountIds = query.getResultList();

					log.debug("accountIds : {}" , accountIds);

					for (Long accountId : accountIds) {
						if(accountId!=null) {
							String accountCode = accountRepo.find(accountId).getCode();
							List<MoveLine> moveLines = moveLineRepo.all().filter("self.account.id = ?1 AND (self.debit > 0 OR self.credit > 0) AND self.date = '"+
							localDate.toString() +"' AND self.move.exportNumber = '"+ exportAgressoRef +"'" + moveLineQueryStr, accountId).fetch();

							log.debug("movelines  : {} " , moveLines);

							if(moveLines.size() > 0) {

								List<MoveLine> moveLineList = moveLineService.consolidateMoveLines(moveLines);

								List<MoveLine> sortMoveLineList = this.sortMoveLineByDebitCredit(moveLineList);

								for(MoveLine moveLine3 : sortMoveLineList)   {

									Journal journal = moveLine3.getMove().getJournal();
									LocalDate date = moveLine3.getDate();
									String items[] = null;

									if(typeSelect == 9)  {
										items = new String[13];
									}
									else {
										items = new String[12];
									}

									items[0] = companyCode;
									items[1] = journal.getExportCode();
									items[2] = moveLine3.getMove().getExportNumber();
									items[3] = String.format("%s", sequence);
									sequence++;
									items[4] = accountCode;

									BigDecimal totAmt = moveLine3.getCredit().subtract(moveLine3.getDebit());
									String moveLineSign = "C";
									if(totAmt.compareTo(BigDecimal.ZERO) == -1) {
										moveLineSign="D";
										totAmt = totAmt.negate();
									}
									items[5] = moveLineSign;
									items[6] = totAmt.toString();


									String analyticAccounts = "";
									for (AnalyticMoveLine analyticDistributionLine : moveLine3.getAnalyticMoveLineList()) {
										analyticAccounts = analyticAccounts + analyticDistributionLine.getAnalyticAccount().getCode() + "/";
									}

									if(typeSelect == 9)  {
										items[7]= "";
										items[8]= analyticAccounts;
										items[9]= String.format("%s DU %s", journal.getCode(), date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
									}
									else  {
										items[7]= analyticAccounts;
										items[8]= String.format("%s DU %s", journal.getCode(), date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
									}

									allMoveLineData.add(items);

								}
							}
						}
					}
				}
			}
		}

		String filePath = accountConfigService.getExportPath(accountConfigService.getAccountConfig(company));
		new File(filePath).mkdirs();

		log.debug("Full path to export : {}{}" , filePath, fileName);
		CsvTool.csvWriter(filePath, fileName, '|',  null, allMoveLineData);
		// Utilisé pour le debuggage
//			CsvTool.csvWriter(filePath, fileName, '|',  this.createHeaderForDetailFile(typeSelect), allMoveLineData);
	}

	
	/**
	 * Méthode permettant de trier une liste en ajoutant d'abord les lignes d'écriture au débit puis celles au crédit
	 * @param moveLineList
	 * 			Une list de ligne d'écriture non triée
	 * @return
	 */
	public List<MoveLine> sortMoveLineByDebitCredit(List<MoveLine> moveLineList)  {
		List<MoveLine> sortMoveLineList = new ArrayList<MoveLine>();
		List<MoveLine> debitMoveLineList = new ArrayList<MoveLine>();
		List<MoveLine> creditMoveLineList = new ArrayList<MoveLine>();
		for(MoveLine moveLine : moveLineList)  {
			if(moveLine.getDebit().compareTo(moveLine.getCredit()) == 1)  {
				debitMoveLineList.add(moveLine);
			}
			else  {
				creditMoveLineList.add(moveLine);
			}
		}
		sortMoveLineList.addAll(debitMoveLineList);
		sortMoveLineList.addAll(creditMoveLineList);
		return sortMoveLineList;
	}

	public String[] createHeaderForPayrollJournalEntry() {
		String header = "JournalCode;"+
                        "JournalLib;"+
                        "EcritureNum;"+
                        "EcritureDate;"+
                        "CompteNum;"+
                        "CompteLib;"+
                        "CompAuxNum;"+
                        "CompAuxLib;"+
                        "PieceRef;"+
                        "PieceDate;"+
                        "EcritureLib;"+
                        "Debit;"+
                        "Credit;"+
                        "EcritureLet;"+
                        "DateLet;"+
                        "ValidDate;"+
                        "Montantdevise;"+
                        "IDevise;";
		return header.split(";");
	}

	public String[] createHeaderForHeaderFile(int typeSelect)  {
		String header = null;
		switch(typeSelect)  {
			case AccountingReportRepository.EXPORT_SALES:
				header = "Société;"+
						"Journal de Vente;"+
						"Numéro d'écriture;"+
						"Date de l'interface;"+
						"Montant de l'écriture;"+
						"Réf. de l'écriture;"+
						"Date de l'écriture;"+
						"Période de l'écriture;";
				return header.split(";");
			case AccountingReportRepository.EXPORT_REFUNDS:
				header = "Société;"+
						"Journal d'Avoir;"+
						"Numéro d'écriture;"+
						"Date de l'interface;"+
						"Montant de l'écriture;"+
						"Réf. de l'écriture;"+
						"Date de l'écriture;"+
						"Période de l'écriture;";
				return header.split(";");
			case AccountingReportRepository.EXPORT_TREASURY:
				header = "Société;"+
						"Journal de Trésorerie;"+
						"Numéro d'écriture;"+
						"Date de l'interface;"+
						"Montant de l'écriture;"+
						"Réf. de l'écriture;"+
						"Date de l'écriture;"+
						"Période de l'écriture;";
				return header.split(";");
			case AccountingReportRepository.EXPORT_PURCHASES:
				header = "Société;"+
						"Journal d'Achat;"+
						"Numéro d'écriture;"+
						"Date de l'interface;"+
						"Code fournisseur;"+
						"Date de la facture;"+
						"Date d'exigibilité;"+
						"Numéro de compte de contrepartie;"+
						"Montant de l'écriture;"+
						"Réf. de l'écriture;"+
						"Date de l'écriture;"+
						"Période de l'écriture;";
				return header.split(";");
			default:
				return null;
		}
	}


	public String[] createHeaderForDetailFile(int typeSelect)  {
		String header = "";

		if(typeSelect == AccountingReportRepository.EXPORT_PURCHASES)  {
			header = "Société;"+
					"Journal;"+
					"Numéro d'écriture;"+
					"Num. ligne d'écriture;"+
					"Numéro de compte;"+
					"Sens de l'écriture;"+
					"Montant de la ligne;"+
					"Code TVA;"+
					"CRB;"+
					"Site;"+
					"Métier;"+
					"Activité;"+
					"Nom;";
		}
		else  {
			header = "Société;"+
					"Journal;"+
					"Numéro d'écriture;"+
					"Num. ligne d'écriture;"+
					"Numéro de compte;"+
					"Sens de l'écriture;"+
					"Montant de la ligne;"+
					"CRB;"+
					"Site;"+
					"Métier;"+
					"Activité;"+
					"Nom;";
		}

		return header.split(";");

	}


	public void exportMoveLine(AccountingReport accountingReport) throws AxelorException, IOException  {

		accountingReportService.setStatus(accountingReport);

		switch (accountingReport.getTypeSelect()) {
		case AccountingReportRepository.EXPORT_SALES:

			this.exportMoveLineTypeSelect1006(accountingReport, false);
			break;

		case AccountingReportRepository.EXPORT_REFUNDS:

			this.exportMoveLineTypeSelect1007(accountingReport, false);
			break;

		case AccountingReportRepository.EXPORT_TREASURY:

			this.exportMoveLineTypeSelect1008(accountingReport, false);
			break;

		case AccountingReportRepository.EXPORT_PURCHASES:

			this.exportMoveLineTypeSelect1009(accountingReport, false);
			break;

		case AccountingReportRepository.EXPORT_GENERAL_BALANCE:
			exportMoveLineTypeSelect1010(accountingReport);
			break;

		case AccountingReportRepository.EXPORT_ADMINISTRATION:
			this.exportMoveLineTypeSelect1000(accountingReport, true, false);
			break;

		case AccountingReportRepository.EXPORT_PAYROLL_JOURNAL_ENTRY:
			this.exportMoveLineTypeSelect1000(accountingReport, false, false);
			break;

		default:
			break;
		}

	}

	public void replayExportMoveLine(AccountingReport accountingReport) throws AxelorException, IOException {
		switch(accountingReport.getTypeSelect()) {
			case AccountingReportRepository.EXPORT_SALES:
				this.exportMoveLineTypeSelect1006(accountingReport, true);
				break;
			case AccountingReportRepository.EXPORT_REFUNDS:
				this.exportMoveLineTypeSelect1007(accountingReport, true);
				break;
			case AccountingReportRepository.EXPORT_TREASURY:
				this.exportMoveLineTypeSelect1008(accountingReport, true);
				break;
			case AccountingReportRepository.EXPORT_PURCHASES:
				this.exportMoveLineTypeSelect1009(accountingReport, true);
				break;
			case AccountingReportRepository.EXPORT_PAYROLL_JOURNAL_ENTRY:
				this.exportMoveLineTypeSelect1000(accountingReport, false, true);
				break;
			default:
				break;
			}
	}


	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public AccountingReport createAccountingReport(Company company, int exportTypeSelect, LocalDate startDate, LocalDate endDate) throws AxelorException  {

		AccountingReport accountingReport = new AccountingReport();
		accountingReport.setCompany(company);
		accountingReport.setTypeSelect(exportTypeSelect);
		accountingReport.setDateFrom(startDate);
		accountingReport.setDateTo(endDate);
		accountingReport.setStatusSelect(AccountingReportRepository.STATUS_DRAFT);
		accountingReport.setDate(appAccountService.getTodayDateTime().toLocalDate());
		accountingReport.setRef(accountingReportService.getSequence(accountingReport));
		
		accountingReportService.buildQuery(accountingReport);

		BigDecimal debitBalance = accountingReportService.getDebitBalance();
		BigDecimal creditBalance = accountingReportService.getCreditBalance();

		accountingReport.setTotalDebit(debitBalance);
		accountingReport.setTotalCredit(creditBalance);
		accountingReport.setBalance(debitBalance.subtract(creditBalance));

		accountingReportRepo.save(accountingReport);

		return accountingReport;

	}

	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public String setFileName(AccountingReport accountingReport) throws AxelorException, IOException{
		Company company =accountingReport.getCompany();
		Partner partner = company.getPartner();
		
		//Pour le moment: on utilise le format par défaut: SIREN+FEC+DATE DE CLÔTURE DE L'EXERCICE.Extension
		String fileName = partnerService.getSIRENNumber(partner)+"FEC";
		// On récupère la date de clôture de l'exercice/période
		if (accountingReport.getDateTo() != null){
			fileName += accountingReport.getDateTo().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD));
		}else if(accountingReport.getPeriod() != null){
			fileName += accountingReport.getPeriod().getToDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD));
		}else if(accountingReport.getYear() != null){
			fileName += accountingReport.getYear().getToDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD));
		}
		else {
			throw new AxelorException(I18n.get(IExceptionMessage.MOVE_LINE_EXPORT_YEAR_OR_PERIOD_OR_DATE_IS_NULL), TraceBackRepository.CATEGORY_NO_VALUE);
		}
		fileName +=".csv";

		return fileName;
	}

}
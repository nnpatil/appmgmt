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
package com.axelor.apps.base.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.nio.file.Path;
import java.text.ParseException;

import com.axelor.apps.base.db.ICalendar;
import com.axelor.apps.base.db.ImportConfiguration;
import com.axelor.apps.base.db.repo.ICalendarRepository;
import com.axelor.apps.base.exceptions.IExceptionMessage;
import com.axelor.apps.base.ical.ICalendarException;
import com.axelor.apps.base.ical.ICalendarService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.fortuna.ical4j.connector.ObjectNotFoundException;
import net.fortuna.ical4j.connector.ObjectStoreException;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.ConstraintViolationException;
import net.fortuna.ical4j.model.ValidationException;

@Singleton
public class ICalendarController {

	@Inject
	private ICalendarService calendarService;
	
	@Inject
	private MetaFiles metaFiles;
	
	public void exportCalendar(ActionRequest request, ActionResponse response) throws IOException, ParserException, ValidationException, ObjectStoreException, ObjectNotFoundException, ParseException {
		ICalendar cal = request.getContext().asType(ICalendar.class);
		Path tempPath = MetaFiles.createTempFile(cal.getName(), ".ics");
		calendarService.export(cal, tempPath.toFile());
		metaFiles.attach(new FileInputStream(tempPath.toFile()), cal.getName() + ".ics", cal);
		response.setReload(true);
	}
	
	public void importCalendarFile(ActionRequest request, ActionResponse response) throws IOException, ParserException
	{

		ImportConfiguration imp = request.getContext().asType(ImportConfiguration.class);
		Object object = request.getContext().get("_id");
		ICalendar cal = null;
		if(object != null){
			Long id = Long.valueOf(object.toString());
			cal = Beans.get(ICalendarRepository.class).find(id);
		}
		
		if(cal == null){
			cal = new ICalendar();
		}
		
		
		File data = MetaFiles.getPath( imp.getDataMetaFile() ).toFile();

		calendarService.load(cal, data);
		response.setCanClose(true);
		response.setReload(true);
		
	}
	
	public void importCalendar(ActionRequest request, ActionResponse response) throws IOException, ParserException 
	{
		ICalendar cal = request.getContext().asType(ICalendar.class);
		response.setView(ActionView
					  		.define(I18n.get(IExceptionMessage.IMPORT_CALENDAR))
					  		.model("com.axelor.apps.base.db.ImportConfiguration")
					  		.add("form", "import-icalendar-form")
					  		.param("popup", "reload")
					  		.param("forceEdit", "true")
					  		.param("show-toolbar", "false")
					  		.param("show-confirm", "false")
					  		.param("popup-save", "false")
					  		.context("_id", cal.getId())
					  		.map());
	}
	
	public void testConnect(ActionRequest request, ActionResponse response) throws Exception
	{
		ICalendar cal = request.getContext().asType(ICalendar.class);
		if (calendarService.testConnect(cal))
			response.setValue("isValid", true);
		else
			response.setAlert("Login and password do not match.");
		
	}
	
	public void synchronizeCalendar(ActionRequest request, ActionResponse response) throws MalformedURLException, SocketException, ObjectStoreException, ObjectNotFoundException, ConstraintViolationException, ICalendarException {
		ICalendar cal = request.getContext().asType(ICalendar.class);
		cal = Beans.get(ICalendarRepository.class).find(cal.getId());
		calendarService.sync(cal);
		response.setReload(true);
	}


	public void validate(ActionRequest request, ActionResponse response) {

		if (request.getContext().get("newPassword") != null)
			response.setValue("password", calendarService.getCalendarEncryptPassword(request.getContext().get("newPassword").toString()));
	}

}




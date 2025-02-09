package catering.order;

import catering.catalog.Option;
import catering.catalog.OptionCatalog;
import catering.catalog.OptionType;
import catering.user.Position;
import catering.user.User;
import catering.user.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDMMType1Font;
import org.salespointframework.inventory.UniqueInventory;
import org.salespointframework.inventory.UniqueInventoryItem;
import org.salespointframework.order.Cart;
import org.salespointframework.order.CartItem;
import org.salespointframework.order.OrderIdentifier;
import org.salespointframework.order.OrderLine;
import org.salespointframework.order.OrderManagement;
import org.salespointframework.order.OrderStatus;
import org.salespointframework.order.Totalable;
import org.salespointframework.payment.Cash;
import org.salespointframework.quantity.Quantity;
import org.salespointframework.support.ConsoleWritingMailSender;
import org.salespointframework.useraccount.UserAccount;
import org.salespointframework.useraccount.web.LoggedIn;
import org.springframework.data.util.Streamable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@PreAuthorize(value = "isAuthenticated()")
@SessionAttributes("cart")
public class OrderController {

	private final OrderManagement<CateringOrder> orderManagement;
	private final CateringOrderRepository orderRepository;
	private final OptionCatalog catalog;
	private final UserRepository userRepository;
	private UniqueInventory<UniqueInventoryItem> inventory;
	private IncomeOverview incomeOverview;

	public OrderController(OrderManagement<CateringOrder> orderManagement, CateringOrderRepository orderRepository,
							OptionCatalog catalog, UserRepository userRepository, IncomeOverview incomeOverview,
						   UniqueInventory<UniqueInventoryItem> inventory) {
		this.orderManagement = orderManagement;
		this.orderRepository = orderRepository;
		this.catalog = catalog;
		this.incomeOverview = incomeOverview;

		this.userRepository = userRepository;
		this.inventory = inventory;
	}

	@GetMapping(value = "/order-history")
	@PreAuthorize(value = "hasRole('CUSTOMER')")
	public String getOrderHistoryForCurrentUser(@LoggedIn UserAccount account, Model model) {
		Iterable<CateringOrder> userOrders = orderManagement.findBy(account);
		Map<OrderIdentifier, String> orderTypes = new HashMap<>();
		model.addAttribute("userOrders", userOrders);
		return "order-history";
	}

	@GetMapping("/cancel-order")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'CUSTOMER')")
	public String cancelOrder(@LoggedIn UserAccount account, @RequestParam("orderId") OrderIdentifier orderId) {
		if (account == null || orderId == null) {
			return "redirect:/login";
		}
		// Bestellung ist da, und aktueller Nutzer hat diese Bestellung in seinem Verlauf
		if(orderManagement.contains(orderId) && orderManagement.get(orderId).get().getUserAccount().equals(account)){
			CateringOrder cateringOrder =  orderManagement.get(orderId).get();
			ConsoleWritingMailSender mailSender = new ConsoleWritingMailSender();
			boolean isCancelDoneBefore3Days = cateringOrder.getCompletionDate().minusDays(3L).isAfter(LocalDate.now());
			orderManagement.cancelOrder(cateringOrder, "None");

			if(isCancelDoneBefore3Days) {
				mailSender.send(cancelConfirmationMessage(cateringOrder, true));
			}else{
				mailSender.send(cancelConfirmationMessage(cateringOrder, false));
			}
		}
		return "redirect:/order-history";
	}

	@ModelAttribute("cart")
    Cart initializeCart(){
        return new Cart();
    }
	// Initial wird eine Übersicht der letzten 30 Tage zurückgegeben, exeklusive des aktuellen Tages
	@GetMapping("/income-overview")
	public String displayIncomeOverview(@RequestParam("startDate") Optional<String> startDate,
										@RequestParam("endDate") Optional<String> endDate, Model model) {
		LocalDate start;
		LocalDate end;
		if (startDate.isEmpty() || endDate.isEmpty()) {
			start = LocalDate.now().minusDays(30L);
			end = LocalDate.now().minusDays(1L);
		} else {
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			start = LocalDate.parse(startDate.get(), formatter);
			end = LocalDate.parse(endDate.get(), formatter);
		}
		if(start.isAfter(end)){
			start  = end.minusDays(30L);
		}

		model.addAttribute("totalIncome", incomeOverview.totalIncome(start, end));
		model.addAttribute("statusPercentages", incomeOverview.statusPercentages(start, end));
		model.addAttribute("servicePercentages" , incomeOverview.servicePercentages(start, end));
		model.addAttribute("start", start);
		model.addAttribute("end", end);

		return "income-overview";
	}

	/**
	 * 
	 * in dependence of @param service the correct orderform will be shown
	 * @param model, for orderreview und checkout
	 * @param order to save the details of the order
	 * @return order_form
	 */
	@GetMapping("/order/{service}")
	public String getOrderForm(@PathVariable String service, Model model, CateringOrder order) {

		Assert.isTrue((service.equals("eventcatering") || service.equals("partyservice") || service.equals("rentacook") ||
				service.equals("mobilebreakfast")), "Service must be valid");

		Streamable<Option> optionStreamable = catalog.findByCategory(service);

		List<OrderFormitem> foodFormitemList = new ArrayList<>();
		List<OrderFormitem> equipFormitemList = new ArrayList<>();
		for (Option option : optionStreamable) {
			if (option.getType() == OptionType.FOOD) {
				foodFormitemList.add(new OrderFormitem(option.getName(), //
					option.getPrice().getNumber().numberValue(Float.class), option.getPersonCount(), 1));
			}
			if (option.getType() == OptionType.EQUIP || option.getType() == OptionType.GOODS) {
				equipFormitemList.add(new OrderFormitem(option.getName(), //
					option.getPrice().getNumber().numberValue(Float.class), option.getPersonCount(), 1));
			}
		}

		OrderForm form = new OrderForm();
		form.setService(service);
		form.setFoodList(foodFormitemList);
		form.setEquipList(equipFormitemList);

		model.addAttribute("form", form);
		model.addAttribute("order", order);

		return "order_form";
	}

	/**
	 * 
	 * @param model for Orderreview
	 * @param order save details
	 * @param form	save details and items for cart
	 * @param cart save CartItems for checkout
	 * @return orderreview
	 */

	@PostMapping("/cartadd")
	String addtoCart(Model model, @ModelAttribute ("order") CateringOrder order, @ModelAttribute ("form") OrderForm form,
					 @ModelAttribute Cart cart ){
		cart.clear();

		model.addAttribute("order", order);
		model.addAttribute("form", form);

		int guestcount = form.getPersons();

		String daytime = order.getTimeString();
		switch(daytime){
			default:
			break;
			case("Früh"):
			order.setTime(TimeSegment.FRÜH);
			break;
			case("Mittag"):
			order.setTime(TimeSegment.MITTAG);
			break;
			case("Abend"):
			order.setTime(TimeSegment.ABEND);
			break;
		}

		// calculates the amount of waiter and chefs in dependence of the servicetype and saves it for the order
		order.setChefcount(calcWorker(form.getService(), guestcount).get(0));
		order.setWaitercount(calcWorker(form.getService(), guestcount).get(1));

		




		//if there aren't enough waiters or chefs it redirects to the orderform
		Streamable<User> chefcountRep = userRepository.getUserByPositionIn(List.of(Position.COOK));
        Streamable<User> waitercountRep = userRepository.getUserByPositionIn(List.of(Position.WAITER,
				Position.EXPERIENCED_WAITER, Position.MINIJOB));
        if(chefcountRep.toList().size() < order.getChefcount() ||
				waitercountRep.toList().size() < order.getWaitercount()){
			return "redirect";
        }

		for (OrderFormitem optionItem : form.getFoodList()) {
			if (optionItem.getAmount() != 0){

				cart.addOrUpdateItem(catalog.findByName(optionItem.getName()).stream().findFirst().get(),
						Quantity.of(optionItem.getAmount()));
			}
		}

		for (OrderFormitem optionItem : form.getEquipList()) {
			if (optionItem.getAmount() != 0){

				cart.addOrUpdateItem(catalog.findByName(optionItem.getName()).stream().findFirst().get(),
						Quantity.of(optionItem.getAmount()));
			}
		}

		return "orderreview";

	}

	@GetMapping("/confirmOrder")
	String confirmOrderPage(){
		return "confirmOrder";
	}

	/**
	 * 
	 * @param cart
	 * @param form
	 * @return order_form for specific service
	 */

	@PostMapping("/redirect")
		public String redirect(@ModelAttribute Cart cart, @ModelAttribute ("form") OrderForm form) {
			if (form.getService().equals("rentacook")){
                cart.clear();
                return "redirect:/order/rentacook";
            }else if (form.getService().equals("eventcatering")){
                cart.clear();
                return "redirect:/order/eventcatering";
            }else if (form.getService().equals("partyservice")){
                cart.clear();
                return "redirect:/order/partyservice";
            }else if (form.getService().equals("mobilebreakfast")){
                cart.clear();
                return "redirect:/order/mobilebreakfast";
            }
        cart.clear();
		return "redirect:/";
		}

	/**
	 * 
	 * @param cart
	 * @param userAccount to save the order on the Useraccount
	 * @param help
	 * @param orderOut gives details to actual order to save
	 * @return confirmOrder.html
	 */
	@PostMapping("/checkout")
    String buy(@ModelAttribute Cart cart, @LoggedIn Optional<UserAccount> userAccount, Errors help,
               @ModelAttribute ("order") CateringOrder orderOut) {

				


        	return userAccount.map(account -> {

				//creates new Order with details and adding equipItems, since it is reusable

				var order = new CateringOrder(account, Cash.CASH, orderOut.getCompletionDate(),
						orderOut.getTime(), orderOut.getAddress(), orderOut.getService());

				for (CartItem ci : cart){
					if(catalog.findByName(ci.getProductName()).stream().findFirst().get().getType() == OptionType.EQUIP){
						saveInventoryItem(catalog.findByName(ci.getProductName()).stream().findFirst().get(), ci.getQuantity());
					}
				}

				cart.addItemsTo(order);

				orderManagement.payOrder(order);

				ArrayList<User> staffList = new ArrayList<>();
				sort(staffList, staffList.size());
				

				//creating Lists of Waiters and Chefs 
				ArrayList<User> orderStaffList = new ArrayList<>();
				Streamable<User> staff = userRepository.getUserByPositionIn(List.of(Position.EXPERIENCED_WAITER, Position.WAITER, Position.MINIJOB));

				staffList = getListof(staff);

				ArrayList<User> chefList = new ArrayList<>();
				Streamable<User> chef = userRepository.getUserByPositionIn(List.of(Position.COOK));

				chefList = getListof(chef);


				
		
				

				//sorting lists
				sort(staffList, staffList.size());
				sort(chefList, chefList.size());

				if(chefList.size() >= orderOut.getChefcount() && staffList.size() >= orderOut.getWaitercount()){

					//checking workcount of Users and add the worker with lowest workcount, for a decent distribution
					ArrayList<User> allstaff = new ArrayList<>();

					allstaff.addAll(getWorkerList(chefList, orderOut.getChefcount()));
					allstaff.addAll(getWorkerList(staffList, orderOut.getWaitercount()));

					for (User u : allstaff){
						u.setWorkcount(u.getWorkcount()+1);
						userRepository.save(u);
					}
					order.setAllocStaff(allstaff);
				}

				if (help.hasErrors()){
					return "cart";
				}
				cart.clear();

				return "redirect:/confirmOrder";
			}).orElse("redirect:/");
		}

		/**
		 * 
		 * @param service
		 * @param guestcount
		 * @return List for setting amount of worker needed
		 */

		public ArrayList<Integer> calcWorker(String service, int guestcount){
			ArrayList<Integer> workerAmount = new ArrayList<>();
			int chefcount;
			int waitercount;
			switch(service){
				default:
				break;
				case "eventcatering":
				guestcount = guestcount / 10;
				if (guestcount == 0){
					guestcount = 1;
				}
				chefcount = guestcount * 4;
				waitercount = guestcount * 5;
				workerAmount.add(chefcount);
				workerAmount.add(waitercount);
				return workerAmount;
				
				case "partyservice":
				guestcount = guestcount / 10;
				if (guestcount == 0){
					guestcount = 1;
				}
				chefcount = guestcount * 3;
				waitercount = guestcount * 4;
				workerAmount.add(chefcount);
				workerAmount.add(waitercount);
				return workerAmount;

				case "rentacook":
				guestcount = guestcount / 5;
				if (guestcount == 0){
					guestcount = 1;
				}
				chefcount = guestcount * 2;
				waitercount = guestcount * 2;
				workerAmount.add(chefcount);
				workerAmount.add(waitercount);
				return workerAmount;

				case "mobilebreakfast":
				guestcount = guestcount / 3;
				if (guestcount == 0){
					guestcount = 1;
				}
				chefcount = 1;
				waitercount = guestcount;
				workerAmount.add(chefcount);
				workerAmount.add(waitercount);
				return workerAmount;
			}
			return workerAmount;
		}

		/**
		 * 
		 * @param workerList
		 * @return workerArrayList
		 * creates List for the needed WorkerType
		 */
		public ArrayList<User> getListof(Streamable<User> workerList){
			ArrayList<User> workerArrayList = new ArrayList<>();
			for(User u : workerList){
				workerArrayList.add(u);
			}
			return workerArrayList;
		}

		/**
		 * 
		 * @param workerList
		 * @param amount
		 * @return workerArrayList
		 * creates List for needed worker for an order
		 */
		public ArrayList<User> getWorkerList(ArrayList<User> workerList, int amount){
			ArrayList<User> workerArrayList = new ArrayList<>();
			for(int i=0; i<amount; i++){
				workerArrayList.add(workerList.get(i));
			}

			return workerArrayList;
		}

		/**
		 * 
		 * @param list
		 * @param n
		 * Sorting List of workers by workcount
		 */
		public void sort(ArrayList<User> list, int n){
			if (n==0){
				return;
			}
			if (n ==1){
				return;
			}

			for (int i=0; i<n-1; i++){
				if (list.get(i).workCount>list.get(i+1).workCount){
					Collections.swap(list, i, i+1);
				}
			}
			sort(list, n-1);

		}

		/**
		 * 
		 * @param option
		 * @param quantity
		 * 
		 * to add Equip Items
		 */
		private void saveInventoryItem(Option option, Quantity quantity) {
			UniqueInventoryItem item = inventory.findByProduct(option).get();
			Quantity quantityInput = quantity;
			item.increaseQuantity(quantity);

			inventory.save(item);
		}

	//get status from buttons and redirect to correct order-list
	@PostMapping("/setstatus")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'STAFF')")
	String list2(@RequestParam("status") String status){
		return "redirect:/order-list/" + status;
	}

	@GetMapping("/order-list/{status}")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'STAFF')")
	String list(Model model, @PathVariable("status") OrderStatus status){
		Iterable<CateringOrder> orders = orderManagement.findBy(status);
		model.addAttribute("orders", orders);
		return "order-list";
	}

	@GetMapping("/order-details/{order-id}")
	String details(Model model, @PathVariable("order-id") OrderIdentifier parameter){
		model.addAttribute("order", orderManagement.get(parameter).get());
		model.addAttribute("account", orderManagement.get(parameter));
		Totalable<OrderLine> orderLines = orderManagement.get(parameter).get().getOrderLines();
		model.addAttribute("details", orderLines);
		return "order-details";
	}

	public static Date LocalDateIntoDate(LocalDate local){
		ZoneId defaultZoneID = ZoneId.of("Europe/Paris");
		return Date.from(local.atStartOfDay(defaultZoneID).toInstant());
	}

	public static int getWeekNumberFromDate(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		return cal.get(Calendar.WEEK_OF_YEAR);
	}

	public static int getYearNumberFromDate(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		return cal.get(Calendar.YEAR);
	}

	//Starting week and year for calendar setup
	public static String YW(Date date){
		int week = getWeekNumberFromDate(date);
		int year = getYearNumberFromDate(date);
		return year + "-" + week;
	}

	//calendar setup
	@GetMapping("/calendar2")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'STAFF')")
	String calendar2(){
		Date date = LocalDateIntoDate(LocalDate.now());
		return "redirect:/calendar/" + YW(date) ;
	}

	//Button for changing the week with redirection to calendar
	@PostMapping("/setweek/{YW}")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'STAFF')")
	String setweek(@RequestParam("button") String button, @PathVariable("YW") String YW){
		String [] split = YW.split("-");
		int year = Integer.parseInt(split[0]);
		int week = Integer.parseInt(split[1]);

		if (button.equals("prev")){
			week -= 1;
			if (week == 0) {
				year -= 1;
				try {
					week = 53;
					String date = year + "-W" + week + "-1";
					LocalDate.parse(date, DateTimeFormatter.ISO_WEEK_DATE);
				} catch (Exception ex) {
					week = 52;
				}
			}
		} else {
			week += 1;
			if (week == 53) {
				try {
					String date = year + "-W" + week + "-1";
					LocalDate.parse(date, DateTimeFormatter.ISO_WEEK_DATE);
				} catch (Exception ex) {
					week = 1;
					year += 1;
				}
			}
			if (week == 54) {
				week = 1;
				year += 1;
			}
		}
		YW = year + "-" + week;
		return "redirect:/calendar/" + YW;
	}

	//calendar with orders
	@GetMapping("/calendar/{YW}")
	@PreAuthorize(value = "hasAnyRole('ADMIN', 'STAFF')")
	String calendar(Model model, @PathVariable("YW") String YW){
		String [] split = YW.split("-");
		int year = Integer.parseInt(split[0]);
		int week = Integer.parseInt(split[1]);

		LocalDate date = LocalDate.of(year, Month.JANUARY, 10);
		LocalDate dayInWeek = date.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week);

		model.addAttribute("YW", YW);
		model.addAttribute("week", week);
		model.addAttribute("year", year);
		model.addAttribute("monday", dayInWeek.with(DayOfWeek.MONDAY));
		model.addAttribute("tuesday", dayInWeek.with(DayOfWeek.TUESDAY));
		model.addAttribute("wednesday", dayInWeek.with(DayOfWeek.WEDNESDAY));
		model.addAttribute("thursday", dayInWeek.with(DayOfWeek.THURSDAY));
		model.addAttribute("friday", dayInWeek.with(DayOfWeek.FRIDAY));
		model.addAttribute("saturday", dayInWeek.with(DayOfWeek.SATURDAY));
		model.addAttribute("sunday", dayInWeek.with(DayOfWeek.SUNDAY));

		Iterable<CateringOrder> fruh = orderRepository.findByOrderStatusAndTime(OrderStatus.PAID, TimeSegment.FRÜH);
		model.addAttribute("fruh", fruh);
		Iterable<CateringOrder> mittag = orderRepository.findByOrderStatusAndTime(OrderStatus.PAID, TimeSegment.MITTAG);
		model.addAttribute("mittag", mittag);
		Iterable<CateringOrder> abend = orderRepository.findByOrderStatusAndTime(OrderStatus.PAID, TimeSegment.ABEND);
		model.addAttribute("abend", abend);

		return "calendar";
	}

	SimpleMailMessage cancelConfirmationMessage(CateringOrder cateringOrder, boolean cancellationBefore3Days){

		SimpleMailMessage simpleMessage = new SimpleMailMessage();
		simpleMessage.setSubject("\n\nStornierungsbestätigung Ihrer Bestellung am " +
				cateringOrder.getCompletionDate().toString());
		String message = "\nSehr geehrte(r) Frau/Herr " + cateringOrder.getUserAccount().getLastname()
				+ ",\n\nSie haben erfolgreich ihre Bestellung storniert."
				+ "\nFälligkeitsdatum: " + cateringOrder.getCompletionDate() + "."
				+ "\nGebuchter Betrag: " + cateringOrder.getTotal().getNumber();
		if(!cancellationBefore3Days) {
			message = message + "\nZu entrichtende Stornierungsgebühren in Höhe von 40% betragen: "
					+ cateringOrder.getTotal().multiply(40L).divide(100L);
		}
		message += "\nSie werden  den gebuchten Betrag innerhalb 7 Werktage bekommen."
				+ "\n\nMit freundlichen Grüßen."
				+ "\n\nMampf-Team";

		simpleMessage.setText(message);
		return simpleMessage;
	}

	@GetMapping(value = "/bill/{orderId}")
	@PreAuthorize(value="hasRole('CUSTOMER')")
	@ResponseBody void printBill(@PathVariable("orderId") OrderIdentifier orderId, HttpServletResponse response) throws IOException {
		CateringOrder order = orderManagement.get(orderId).get();

		String property = System.getProperty("java.io.tmpdir");
		File tempDir = new File(property);
		File bill = File.createTempFile(order.getService() + "_" + order.getCompletionDate().toString(), ".pdf", tempDir);
		InputStream stream;

		PDDocument document = new PDDocument();
		PDPage page = new PDPage();
		document.addPage(page);

		try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
			createPdf(contentStream, document,  order).save(bill);
			document.save(bill);
			response.setContentType("application/pdf");
			response.setHeader("Content-Disposition", String.format("attachment; filename=\"" + bill.getName() + "\""));
			stream = new BufferedInputStream(new FileInputStream(bill));
			FileCopyUtils.copy(stream, response.getOutputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private PDDocument createPdf(PDPageContentStream contentStream, PDDocument document, CateringOrder order) throws IOException {
		contentStream.beginText();

		int xOffset = 65;
		int xOffsetData = 210;

		contentStream.setFont(PDMMType1Font.HELVETICA_BOLD, 28);
		contentStream.setNonStrokingColor(0.31f, 0.01f, 0.01f);
		contentStream.newLineAtOffset(xOffset, 700);
		contentStream.showText("Cateringservice ");

		contentStream.setFont(PDMMType1Font.TIMES_ITALIC, 28);
		contentStream.setNonStrokingColor(0.31f, 0.01f, 0.01f);
		contentStream.showText("Mampf");

		contentStream.setFont(PDMMType1Font.HELVETICA_BOLD, 24);
		contentStream.setNonStrokingColor(0.12f, 0.36f, 0.95f);
		contentStream.newLineAtOffset(400, -30);
		contentStream.showText("Rechnung");
		contentStream.endText();

		contentStream.setFont(PDMMType1Font.HELVETICA, 18);
		contentStream.setNonStrokingColor(Color.BLACK);
		insertText(contentStream, xOffset, 600, "Bestellung von " + order.getUserAccount().getFirstname()
				+ " " + order.getUserAccount().getLastname());

		contentStream.setFont(PDMMType1Font.HELVETICA, 13);

		insertText(contentStream,xOffset, 570, "Email:");
		insertText(contentStream,xOffsetData, 570, order.getUserAccount().getEmail());

		insertText(contentStream,xOffset, 550, "Datum:");
		insertText(contentStream,xOffsetData, 550, order.getCompletionDate().toString() +
				", " + order.getTime().toString().toLowerCase());

		insertText(contentStream,xOffset, 530, "Adresse:");
		insertText(contentStream,xOffsetData, 530, order.getAddress());

		int yOffsetForLastLine = 490;
		insertText(contentStream,xOffset, 510, "Ausgewählte Optionen:");
		for(OrderLine option : order.getOrderLines().toList()){
			insertText(contentStream, xOffsetData, yOffsetForLastLine, "- " + option.getProductName() + " "
					+ option.getQuantity() + "x");
			yOffsetForLastLine -= 20;
		}

		insertText(contentStream,xOffset, yOffsetForLastLine -20, "Kosten:");
		contentStream.setFont(PDMMType1Font.HELVETICA_BOLD, 13);
		insertText(contentStream,xOffsetData, yOffsetForLastLine - 20, order.getTotal().getNumber() +" EUR");

		contentStream.close();
		return document;
	}

	private void insertText(PDPageContentStream contentStream,int xOffset, int yOffset, String text) throws IOException {
		contentStream.beginText();
		contentStream.newLineAtOffset(xOffset, yOffset);
		contentStream.showText(text);
		contentStream.endText();
	}
}

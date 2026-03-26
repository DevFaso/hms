# Local Testing Guide - MyChart Patient Mobile App

## 🚀 Quick Setup (5 Minutes)

### Step 1: Prerequisites Check
```bash
# Check Node.js version (should be 18+)
node --version

# Check npm version (should be 8+)
npm --version

# If you don't have Node.js, download from: https://nodejs.org/
```

### Step 2: Install and Run
```bash
# Extract the zip file (if applicable)
unzip patient-mobile-app.zip
cd patient-mobile-app

# Install dependencies (choose one)
npm install
# OR (faster)
pnpm install

# Start the development server
npm run dev
# OR
pnpm run dev

# Open browser to: http://localhost:5173
```

### Step 3: Backend API (Optional)
The app connects to the Hospital Management System API at `http://localhost:8081/api`.
If the backend is not running, the app gracefully falls back to mock data.

```bash
# Copy and edit the environment file
cp .env.example .env

# The default is:
# VITE_API_URL=http://localhost:8081/api
```

**API endpoints used (Patient Portal):**
| Feature             | Method | Endpoint                              |
|---------------------|--------|---------------------------------------|
| Login               | POST   | `/auth/login`                         |
| Logout              | POST   | `/auth/logout`                        |
| Token Refresh       | POST   | `/auth/token/refresh`                 |
| Profile             | GET    | `/me/patient/profile`                 |
| Update Profile      | PUT    | `/me/patient/profile`                 |
| Appointments        | GET    | `/me/patient/appointments`            |
| Cancel Appointment  | PUT    | `/me/patient/appointments/cancel`     |
| Reschedule          | PUT    | `/me/patient/appointments/reschedule` |
| Schedule (create)   | POST   | `/appointments`                       |
| Check Availability  | GET    | `/availability/check`                 |
| Lab Results         | GET    | `/me/patient/lab-results`             |
| Medications         | GET    | `/me/patient/medications`             |
| Billing Invoices    | GET    | `/me/patient/billing/invoices`        |
| Notifications       | GET    | `/notifications`                      |
| Mark Read           | POST   | `/notifications/{id}/read`            |
| Conversations       | GET    | `/chat/conversations/{userId}`        |
| Chat History        | GET    | `/chat/history/{u1}/{u2}`             |
| Send Message        | POST   | `/chat/send`                          |
| Mark Chat Read      | PUT    | `/chat/mark-read/{s}/{r}`             |
| Care Team           | GET    | `/me/patient/care-team`               |
| Visit History       | GET    | `/me/patient/encounters`              |
| After Visit Summary | GET    | `/me/patient/after-visit-summaries`   |
| Prescriptions       | GET    | `/me/patient/prescriptions`           |
| Consents            | GET    | `/me/patient/consents`                |
| Grant Consent       | POST   | `/me/patient/consents`                |
| Revoke Consent      | DELETE | `/me/patient/consents`                |
| Refill Request      | POST   | `/me/patient/refills`                 |
| Health Summary      | GET    | `/me/patient/health-summary`          |
| Staff List          | GET    | `/staff`                              |

## 🧪 Complete Testing Checklist

### ✅ Authentication Testing
- [ ] **Face ID Login**: Click "Log in with Face ID" button
- [ ] **Username/Password**: Click "Or log in with username and password"
- [ ] **Help Section**: Test "Need help?" and "Sign up" buttons
- [ ] **Get Help**: Test the "Get Help" section at bottom

### ✅ Dashboard Testing
- [ ] **Welcome Message**: Verify "Welcome, Tiego!" appears
- [ ] **Edit Button**: Test the edit button (✏️) next to welcome message
- [ ] **Feature Tiles**: Test all 6 main tiles:
  - Schedule an Appointment
  - Messages
  - Visits
  - Test Results
  - Medications
  - Billing
- [ ] **Notifications**: Verify "New Test Result" and "Amount Due" cards appear

### ✅ Navigation Testing
- [ ] **Hamburger Menu**: Click menu button (☰) in header
- [ ] **Menu Categories**: Verify all sections appear:
  - Find Care
  - Communication
  - My Record
  - Billing
- [ ] **Search Function**: Test search bar in menu
- [ ] **Menu Items**: Click each menu item to test navigation
- [ ] **Back Navigation**: Test back arrows work correctly

### ✅ Appointments/Visits Testing
- [ ] **Future Appointments**: Verify upcoming appointments display
  - Lab Work (Dec 5, 2025)
  - Revisit with Dr. Joshua Shapiro (Dec 12, 2025)
- [ ] **Past Appointments**: Verify completed visits show
  - Hospital Outpatient Visit (Sep 12, 2025)
- [ ] **After Visit Summary**: Test "View After Visit Summary" button
- [ ] **Schedule Button**: Test "Schedule an appointment" button

### ✅ Messages Testing (MVP2 — Full Chat)
- [ ] **Conversation List**: Verify conversations appear with avatars
- [ ] **Unread Badges**: Check unread counts on conversations
- [ ] **Search Messages**: Test search bar filters conversations
- [ ] **Open Thread**: Tap conversation to open chat thread
- [ ] **Chat Bubbles**: Verify sent (blue, right) and received (gray, left) bubbles
- [ ] **Date Separators**: Check "Today", "Yesterday", and date labels
- [ ] **Send Message**: Type and send a message, verify optimistic display
- [ ] **Compose New**: Click "New" button → recipient picker → compose → send
- [ ] **Recipient Search**: Search providers in compose screen
- [ ] **Success Screen**: Verify "Message Sent" confirmation

### ✅ Scheduling Testing (MVP2)
- [ ] **Schedule Button**: Click "Schedule" on Appointments page → opens wizard
- [ ] **Step 1 — Provider**: Verify provider list with search
- [ ] **Step 2 — Date**: Select date on calendar (weekdays only)
- [ ] **Step 2 — Time**: Select time slot from grid
- [ ] **Step 3 — Confirm**: Review appointment details
- [ ] **Reason/Notes**: Enter visit reason and notes
- [ ] **Submit**: Confirm and see success screen
- [ ] **Step Navigation**: Back/forward through steps works
- [ ] **Step Indicator**: 3-step progress dots update

### ✅ Visit History Testing (MVP2)
- [ ] **Visit List**: Verify encounters display with provider, date, facility
- [ ] **Status Badges**: Check COMPLETED badges
- [ ] **Diagnoses Tags**: Verify diagnosis badges on visits
- [ ] **After Visit Summary**: Click "View After Visit Summary®"
- [ ] **Summary Detail**: Verify diagnoses, vitals, instructions, medications, labs
- [ ] **Follow-up Info**: Check follow-up date and referrals display
- [ ] **Download/Print**: Verify download and print buttons present

### ✅ Care Team Testing (MVP2)
- [ ] **PCP Card**: Verify primary care provider with PCP badge
- [ ] **Team Members**: Check specialist and care coordinator cards
- [ ] **Contact Actions**: Test Message, Call, Email buttons
- [ ] **Facility Info**: Verify MapPin + facility names
- [ ] **Message Link**: Click "Message" → navigates to chat thread

### ✅ Documents Testing (MVP2)
- [ ] **Document List**: Verify all document categories display
- [ ] **Tab Filtering**: Test All, Visit Notes, Prescriptions, Lab Reports, etc.
- [ ] **Search**: Test document search
- [ ] **Category Icons**: Verify color-coded icons per category
- [ ] **Download Button**: Check download icon on downloadable docs
- [ ] **Consents Link**: Click "Consents" button → navigates to consent form

### ✅ Forms & Consents Testing (MVP2)
- [ ] **Consent List**: Verify active consents with hospital names
- [ ] **Info Card**: Check "About Data Sharing" explanation
- [ ] **Grant New**: Click "Grant" → fill hospital name → select scope → confirm
- [ ] **Revoke Consent**: Click trash icon → consent removed
- [ ] **Scope Types**: Verify dropdown: Full, Labs only, Medications only, Imaging only

### ✅ Test Results Testing
- [ ] **Results List**: Verify test results display
  - Complete Blood Count (New status)
  - Hemoglobin A1C (Reviewed status)
- [ ] **Status Badges**: Check "New" and "Reviewed" badges
- [ ] **Action Buttons**: Test "View" and "Download" buttons
- [ ] **Provider Info**: Verify provider names appear correctly

### ✅ Medications Testing
- [ ] **Medication List**: Verify medications display
  - Metformin 500mg
  - Lisinopril 10mg
- [ ] **Dosage Information**: Check dosage instructions appear
- [ ] **Prescriber Info**: Verify prescribing doctor names
- [ ] **Refill Status**: Check refill counts display
- [ ] **Refill Buttons**: Test "Request Refill" buttons
- [ ] **Pharmacy Button**: Test "Manage My Pharmacies" button

### ✅ Billing & Payment Testing
- [ ] **Bill List**: Verify bills display with proper status
  - Physician Services ($54.00, Due - red border)
  - Lab Work ($125.00, Paid - green border)
- [ ] **Status Indicators**: Check "Due" and "Paid" badges
- [ ] **Pay Now Button**: Test "Pay Now" button on due bills
- [ ] **Insurance/Estimates**: Test bottom buttons

### ✅ Mobile Money Payment Testing
- [ ] **Payment Options Screen**: Verify payment selection appears
- [ ] **Bill Summary**: Check payment due amount displays ($54.00)
- [ ] **Mobile Money Section**: Verify all options appear:
  - [ ] Orange Money (orange branding)
  - [ ] MTN Mobile Money (yellow branding)
  - [ ] Airtel Money (red branding)
  - [ ] M-Pesa (green branding)
- [ ] **Traditional Methods**: Verify other payment options:
  - [ ] Credit/Debit Card (blue branding)
  - [ ] Bank Transfer (purple branding)
- [ ] **Security Notice**: Check security message appears at bottom
- [ ] **Hover Effects**: Test hover effects on payment options

### ✅ Visual Design Testing
- [ ] **Branding**: Verify NYC Health + Hospitals branding
- [ ] **MyChart Epic Logo**: Check logo appears in header
- [ ] **Color Scheme**: Verify blue gradient backgrounds
- [ ] **Icons**: Check all icons display correctly
- [ ] **Responsive Design**: Test on different screen sizes
- [ ] **Touch Targets**: Verify buttons are easy to tap

### ✅ Interactive Elements Testing
- [ ] **Button Hover**: Test hover effects on buttons
- [ ] **Card Hover**: Test hover effects on cards
- [ ] **Smooth Transitions**: Verify animations work smoothly
- [ ] **Loading States**: Check transitions between screens
- [ ] **Error Handling**: Test navigation edge cases

## 🔧 Troubleshooting

### Common Issues and Solutions

#### Port Already in Use
```bash
# Kill process on port 5173
npx kill-port 5173
# Or use different port
npm run dev -- --port 3000
```

#### Dependencies Not Installing
```bash
# Clear cache and reinstall
rm -rf node_modules package-lock.json
npm cache clean --force
npm install
```

#### App Not Loading in Browser
1. Check console for errors (F12 → Console)
2. Verify development server is running
3. Try different browser
4. Clear browser cache

#### Styling Issues
```bash
# Rebuild Tailwind CSS
npm run build
npm run dev
```

## 📱 Mobile Testing

### Browser Mobile Simulation
1. Open Chrome DevTools (F12)
2. Click device toolbar icon (📱)
3. Select mobile device (iPhone, Android)
4. Test all functionality in mobile view

### Actual Mobile Testing
1. Find your computer's IP address:
   ```bash
   # Windows
   ipconfig
   # Mac/Linux
   ifconfig
   ```
2. Access app from mobile browser: `http://YOUR_IP:5173`
3. Test touch interactions and mobile-specific features

## 🎯 Performance Testing

### Loading Speed
- [ ] Initial page load under 3 seconds
- [ ] Navigation between sections is instant
- [ ] Images and icons load quickly

### Memory Usage
- [ ] Check browser memory usage (DevTools → Performance)
- [ ] No memory leaks during navigation
- [ ] Smooth scrolling and interactions

### Network Testing
- [ ] Test with slow network (DevTools → Network → Slow 3G)
- [ ] Verify app remains functional
- [ ] Check loading states appear appropriately

## 📊 Browser Compatibility Testing

### Desktop Browsers
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)

### Mobile Browsers
- [ ] iOS Safari
- [ ] Chrome Mobile
- [ ] Samsung Internet
- [ ] Firefox Mobile

## 🔍 Accessibility Testing

### Keyboard Navigation
- [ ] Tab through all interactive elements
- [ ] Enter key activates buttons
- [ ] Escape key closes modals/menus

### Screen Reader Testing
- [ ] Use built-in screen reader (Windows Narrator, macOS VoiceOver)
- [ ] Verify all content is readable
- [ ] Check proper heading structure

### Visual Accessibility
- [ ] Test with high contrast mode
- [ ] Verify color contrast ratios
- [ ] Check text is readable at 200% zoom

## 📝 Test Results Documentation

### Create Test Report
```bash
# Create test results file
touch test-results.md

# Document findings
echo "# Test Results - $(date)" >> test-results.md
echo "## Passed Tests:" >> test-results.md
echo "## Failed Tests:" >> test-results.md
echo "## Notes:" >> test-results.md
```

### Sample Test Report Template
```markdown
# Test Results - [Date]

## Environment
- OS: [Windows/Mac/Linux]
- Browser: [Chrome/Firefox/Safari/Edge]
- Screen Size: [Desktop/Mobile]
- Node.js Version: [Version]

## Passed Tests ✅
- Authentication: Face ID login works
- Dashboard: All tiles functional
- Navigation: Menu system working
- [Add more...]

## Failed Tests ❌
- [List any issues found]

## Performance Notes
- Loading time: [X] seconds
- Memory usage: [X] MB
- Network requests: [X] requests

## Recommendations
- [Any suggestions for improvements]
```

## 🎉 Success Criteria

Your testing is complete when:
- [ ] All authentication methods work
- [ ] All navigation functions properly
- [ ] All features display correct data
- [ ] Mobile money payment options appear
- [ ] No console errors
- [ ] Responsive design works on mobile
- [ ] Performance is acceptable

## 📞 Getting Help

If you encounter issues:
1. Check the main README.md file
2. Look for error messages in browser console (F12)
3. Verify all dependencies installed correctly
4. Try restarting the development server
5. Check Node.js and npm versions meet requirements

---

**Happy Testing! 🚀**

*This guide ensures comprehensive testing of all features in the MyChart Patient Mobile App, including the new mobile money payment integration.*

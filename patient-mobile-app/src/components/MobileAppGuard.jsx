import { Smartphone, TabletSmartphone, BellRing, Fingerprint, ShieldCheck } from 'lucide-react'
import { isNative, getPlatform } from '@/services/platform'
import { useIsMobile } from '@/hooks/use-mobile'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'

const portalHighlights = [
  'Appointments, check-in, and visit summaries',
  'Lab results, medications, and vitals',
  'Billing, records, and care team access',
]

export default function MobileAppGuard({ children }) {
  const mobileViewport = useIsMobile()
  const nativePlatform = isNative()
  const platform = getPlatform()

  if (nativePlatform || mobileViewport) {
    return children
  }

  return (
    <div className="min-h-screen bg-slate-950 px-6 py-10 text-white">
      <div className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-6xl flex-col gap-10 lg:flex-row lg:items-center lg:justify-between">
        <section className="max-w-2xl space-y-6">
          <Badge className="bg-emerald-500/20 px-3 py-1 text-emerald-200 hover:bg-emerald-500/20">
            Mobile application shell
          </Badge>
          <div className="space-y-4">
            <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
              MediHub Patient is a mobile app, not a desktop website.
            </h1>
            <p className="max-w-xl text-base leading-7 text-slate-300 sm:text-lg">
              The patient-mobile-app now follows a native-mobile experience modeled on the patient portal
              workflows, with biometric sign-in, push alerts, camera upload support, and bottom-tab
              navigation designed for phones.
            </p>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <Card className="border-white/10 bg-white/5 text-white">
              <CardContent className="flex items-start gap-3 p-4">
                <Fingerprint className="mt-0.5 h-5 w-5 text-cyan-300" />
                <div>
                  <h2 className="font-semibold">Native sign-in</h2>
                  <p className="text-sm text-slate-300">Biometrics and secure credential storage stay front and center.</p>
                </div>
              </CardContent>
            </Card>
            <Card className="border-white/10 bg-white/5 text-white">
              <CardContent className="flex items-start gap-3 p-4">
                <BellRing className="mt-0.5 h-5 w-5 text-cyan-300" />
                <div>
                  <h2 className="font-semibold">Native notifications</h2>
                  <p className="text-sm text-slate-300">Appointment, billing, and lab-result alerts map to mobile push flows.</p>
                </div>
              </CardContent>
            </Card>
            <Card className="border-white/10 bg-white/5 text-white">
              <CardContent className="flex items-start gap-3 p-4">
                <ShieldCheck className="mt-0.5 h-5 w-5 text-cyan-300" />
                <div>
                  <h2 className="font-semibold">Portal workflows</h2>
                  <p className="text-sm text-slate-300">Key patient portal experiences were preserved, then condensed for handheld use.</p>
                </div>
              </CardContent>
            </Card>
            <Card className="border-white/10 bg-white/5 text-white">
              <CardContent className="flex items-start gap-3 p-4">
                <TabletSmartphone className="mt-0.5 h-5 w-5 text-cyan-300" />
                <div>
                  <h2 className="font-semibold">Phone-first layout</h2>
                  <p className="text-sm text-slate-300">Large desktop presentation is intentionally blocked to avoid a website feel.</p>
                </div>
              </CardContent>
            </Card>
          </div>

          <div className="rounded-3xl border border-white/10 bg-white/5 p-5">
            <p className="text-sm font-medium uppercase tracking-[0.2em] text-slate-400">Included patient portal workflows</p>
            <ul className="mt-4 space-y-3 text-sm text-slate-200">
              {portalHighlights.map((item) => (
                <li key={item} className="flex items-start gap-3">
                  <span className="mt-1 h-2.5 w-2.5 rounded-full bg-emerald-400" />
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="mx-auto w-full max-w-sm">
          <div className="rounded-[2.5rem] border border-white/10 bg-slate-900 p-3 shadow-2xl shadow-cyan-950/50">
            <div className="rounded-[2rem] border border-white/10 bg-gradient-to-b from-slate-900 via-slate-900 to-slate-800 p-5">
              <div className="mx-auto mb-5 h-1.5 w-20 rounded-full bg-white/20" />
              <div className="space-y-4">
                <div className="flex items-center justify-between rounded-2xl bg-blue-600 px-4 py-3">
                  <div>
                    <p className="text-xs uppercase tracking-[0.2em] text-blue-100">Native mode</p>
                    <p className="text-lg font-semibold">Open on iOS or Android</p>
                  </div>
                  <Smartphone className="h-6 w-6 text-white" />
                </div>
                <div className="rounded-2xl bg-white p-4 text-slate-900">
                  <p className="text-sm font-semibold">Current desktop platform</p>
                  <p className="mt-1 text-sm text-slate-500">Detected: {platform}</p>
                  <p className="mt-3 text-sm text-slate-600">
                    Use <span className="font-semibold">npm run cap:run:ios</span> or <span className="font-semibold">npm run cap:run:android</span>,
                    or resize the browser to a phone viewport while developing.
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  {['Dashboard', 'Appointments', 'Messages', 'Care Team'].map((item) => (
                    <div key={item} className="rounded-2xl bg-white/8 px-4 py-5 text-center text-sm font-medium text-slate-100">
                      {item}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}

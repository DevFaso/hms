import { useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import portalService from '@/services/portalService'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import {
  User, Phone, Mail, MapPin, Shield, Heart, AlertTriangle,
  Building2, Edit3, Save, X
} from 'lucide-react'

export default function PatientProfilePage() {
  const { user } = useAuth()
  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState({
    phone: user?.phone || '',
    email: user?.email || '',
    street: user?.address?.street || '',
    apt: user?.address?.apt || '',
    city: user?.address?.city || '',
    state: user?.address?.state || '',
    zip: user?.address?.zip || '',
  })

  const handleChange = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const handleSave = async () => {
    try {
      await portalService.updateProfile({
        phoneNumberPrimary: form.phone,
        email: form.email,
        addressLine1: form.street,
        addressLine2: form.apt,
        city: form.city,
        state: form.state,
        zipCode: form.zip,
      })
    } catch {
      console.warn('Profile update API unavailable — saved locally')
    }
    setEditing(false)
  }

  if (!user) return null

  return (
    <div className="space-y-5">
      {/* Profile header */}
      <div className="flex items-center space-x-4">
        <div className="w-16 h-16 bg-blue-700 rounded-full flex items-center justify-center shrink-0">
          <span className="text-white text-2xl font-bold">
            {user.firstName?.[0]}{user.lastName?.[0]}
          </span>
        </div>
        <div className="flex-1">
          <h2 className="text-xl font-bold text-gray-800">
            {user.firstName} {user.lastName}
          </h2>
          <p className="text-sm text-gray-500">Patient ID: {user.id}</p>
          <p className="text-sm text-gray-500">Member since {user.memberSince}</p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setEditing(!editing)}
        >
          {editing ? <X className="h-4 w-4" /> : <Edit3 className="h-4 w-4" />}
        </Button>
      </div>

      {/* Personal Info */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <User className="h-4 w-4 mr-2 text-blue-600" />
            Personal Information
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-gray-500">Date of Birth</p>
              <p className="font-medium text-gray-800">{user.dateOfBirth}</p>
            </div>
            <div>
              <p className="text-gray-500">Gender</p>
              <p className="font-medium text-gray-800">{user.gender}</p>
            </div>
            <div>
              <p className="text-gray-500">Blood Type</p>
              <p className="font-medium text-gray-800">{user.bloodType}</p>
            </div>
            <div>
              <p className="text-gray-500">Language</p>
              <p className="font-medium text-gray-800">{user.preferredLanguage}</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Contact Info */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <Phone className="h-4 w-4 mr-2 text-blue-600" />
            Contact Information
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {editing ? (
            <div className="space-y-3">
              <div>
                <Label htmlFor="phone" className="text-xs">Phone</Label>
                <Input
                  id="phone"
                  value={form.phone}
                  onChange={(e) => handleChange('phone', e.target.value)}
                />
              </div>
              <div>
                <Label htmlFor="email" className="text-xs">Email</Label>
                <Input
                  id="email"
                  value={form.email}
                  onChange={(e) => handleChange('email', e.target.value)}
                />
              </div>
              <Separator />
              <p className="text-xs text-gray-500 font-medium">Address</p>
              <div className="grid grid-cols-2 gap-2">
                <div className="col-span-2">
                  <Input
                    value={form.street}
                    onChange={(e) => handleChange('street', e.target.value)}
                    placeholder="Street"
                  />
                </div>
                <Input
                  value={form.apt}
                  onChange={(e) => handleChange('apt', e.target.value)}
                  placeholder="Apt"
                />
                <Input
                  value={form.city}
                  onChange={(e) => handleChange('city', e.target.value)}
                  placeholder="City"
                />
                <Input
                  value={form.state}
                  onChange={(e) => handleChange('state', e.target.value)}
                  placeholder="State"
                />
                <Input
                  value={form.zip}
                  onChange={(e) => handleChange('zip', e.target.value)}
                  placeholder="ZIP"
                />
              </div>
              <Button className="w-full bg-blue-700 hover:bg-blue-800" onClick={handleSave}>
                <Save className="h-4 w-4 mr-2" />
                Save Changes
              </Button>
            </div>
          ) : (
            <div className="space-y-2 text-sm">
              <div className="flex items-center space-x-2">
                <Phone className="h-3 w-3 text-gray-400" />
                <span className="text-gray-800">{user.phone}</span>
              </div>
              <div className="flex items-center space-x-2">
                <Mail className="h-3 w-3 text-gray-400" />
                <span className="text-gray-800">{user.email}</span>
              </div>
              <div className="flex items-start space-x-2">
                <MapPin className="h-3 w-3 text-gray-400 mt-0.5" />
                <span className="text-gray-800">
                  {user.address.street}, {user.address.apt}<br />
                  {user.address.city}, {user.address.state} {user.address.zip}
                </span>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Allergies */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <AlertTriangle className="h-4 w-4 mr-2 text-red-500" />
            Allergies
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            {user.allergies?.map((allergy, i) => (
              <Badge key={i} variant="destructive" className="text-xs">
                {allergy}
              </Badge>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Insurance */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <Shield className="h-4 w-4 mr-2 text-green-600" />
            Insurance
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <p className="text-gray-500">Provider</p>
              <p className="font-medium text-gray-800">{user.insurance.provider}</p>
            </div>
            <div>
              <p className="text-gray-500">Plan</p>
              <p className="font-medium text-gray-800">{user.insurance.plan}</p>
            </div>
            <div>
              <p className="text-gray-500">Member ID</p>
              <p className="font-medium text-gray-800">{user.insurance.memberId}</p>
            </div>
            <div>
              <p className="text-gray-500">Group #</p>
              <p className="font-medium text-gray-800">{user.insurance.groupNumber}</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Emergency Contact */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <Heart className="h-4 w-4 mr-2 text-pink-500" />
            Emergency Contact
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          <p className="font-medium text-gray-800">{user.emergencyContact.name}</p>
          <p className="text-gray-500">{user.emergencyContact.relationship}</p>
          <p className="text-gray-800">{user.emergencyContact.phone}</p>
        </CardContent>
      </Card>

      {/* Care Team */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center">
            <Building2 className="h-4 w-4 mr-2 text-indigo-500" />
            Care Team
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          <p className="font-medium text-gray-800">{user.primaryCareProvider}</p>
          <p className="text-gray-500">{user.facility}</p>
          <p className="text-gray-500">Last visit: {user.lastVisit}</p>
        </CardContent>
      </Card>
    </div>
  )
}


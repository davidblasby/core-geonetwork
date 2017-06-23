/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

(function() {
  goog.provide('gn_associatedmd_directive');

  var module = angular.module('gn_associatedmd_directive', [
  ]);

  module.directive('gnAssociatedMd', [
    'gnGlobalSettings',
    function() {
      return {
        restrict: 'E',
        scope: {
          entryId: '='
        },
        templateUrl: '../../catalog/components/edit/associatedmd/' +
          'partials/associated-results.html',
        controller: function ($scope, $element, gnGlobalSettings) {
          $scope.searchObj = {
            selectionBucket: 'd101',
            params: {
              _isTemplate: 'y or n',
              any: '',
              sortBy: 'title',
              _xlink: '*'
            },
            sortbyValues: [
              {
                sortBy: 'title'
              },
              {
                sortBy: 'owner'
              },
              {
                sortBy: 'changeDate',
                sortOrder: 'reverse'
              }
            ]
          };
          $scope.paginationInfo = {
            pages: -1,
            currentPage: 1,
            hitsPerPage: 8
          };
          $scope.modelOptions = angular.copy(gnGlobalSettings.modelOptions);

          $scope.$watch('entryId', function (v) {
            if (v) {
              $scope.searchObj.params._xlink =
                '*local://srv/api/registries/entries/' + v + '*';
              $scope.$broadcast('clearResults');
              $scope.$broadcast('search');
            }
          });
        }
      };
    }
  ]);

})();
